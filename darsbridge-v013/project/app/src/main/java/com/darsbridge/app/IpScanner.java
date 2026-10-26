package com.darsbridge.app;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.*;

/** Direct, bounded HTTPS checks. Never changes the browser route, DNS, proxy settings or TLS trust. */
final class IpScanner implements AutoCloseable {
    enum Status { TCP_FAILED, TLS_FAILED, TLS_NO_HTTP, HTTPS_RESPONSE }
    enum End { COMPLETE, FOUND_LIMIT, CANCELLED, NETWORK_CHANGED, TIME_LIMIT }
    static final class Result {
        final String ip;final Status status;final int http;final long millis;
        Result(String ip,Status status,int http,long millis){this.ip=ip;this.status=status;this.http=http;this.millis=millis;}
    }
    interface Connector {
        boolean current();
        void connect(Socket socket,InetAddress address,int port,int timeout)throws IOException;
    }
    interface Listener {
        void result(Result result,int completed,int total);
        void finished(End why,int completed,int total);
    }
    private final Connector connector;private final Listener listener;private final SSLSocketFactory tls;
    private final ExecutorService workers=Executors.newFixedThreadPool(4),coordinator=Executors.newSingleThreadExecutor();
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private final AtomicBoolean started=new AtomicBoolean(),ended=new AtomicBoolean();
    private volatile int completed,total;
    IpScanner(Connector connector,Listener listener){this(connector,listener,(SSLSocketFactory)SSLSocketFactory.getDefault());}
    // Test fixtures may supply a dedicated CA; the product constructor always uses platform trust.
    IpScanner(Connector connector,Listener listener,SSLSocketFactory tls){this.connector=connector;this.listener=listener;this.tls=tls;}
    void start(String input,List<String> candidates){
        String host=IpCandidates.host(input);
        if(candidates.isEmpty() || candidates.size()>IpCandidates.MAX)throw new IllegalArgumentException("تعداد نشانی‌های آزمایش معتبر نیست.");
        List<String> ips=new ArrayList<>(candidates);for(String ip:ips)if(!IpCandidates.isPublic(IpCandidates.number(ip)))throw new IllegalArgumentException("فقط IPv4 عمومی قابل آزمایش است.");
        if(ended.get())throw new IllegalStateException("Scanner closed");
        if(!started.compareAndSet(false,true))throw new IllegalStateException("Scanner already started");
        total=ips.size();
        coordinator.execute(()->{
            CompletionService<Result> queue=new ExecutorCompletionService<>(workers);
            try{
                for(String ip:ips)queue.submit(()->probe(host,ip));
                long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(3);int hits=0;
                while(!ended.get() && completed<total){
                    if(!connector.current()){finish(End.NETWORK_CHANGED);return;}
                    if(System.nanoTime()>=deadline){finish(End.TIME_LIMIT);return;}
                    Future<Result> done=queue.poll(200,TimeUnit.MILLISECONDS);if(done==null)continue;
                    Result r=done.get();if(ended.get())return;
                    if(!connector.current()){finish(End.NETWORK_CHANGED);return;}
                    completed++;listener.result(r,completed,total);
                    if(r.status==Status.HTTPS_RESPONSE && ++hits>=3){finish(End.FOUND_LIMIT);return;}
                }
                finish(End.COMPLETE);
            }catch(InterruptedException e){Thread.currentThread().interrupt();finish(End.CANCELLED);}
            catch(ExecutionException | RuntimeException e){finish(End.CANCELLED);}
        });
    }
    private Result probe(String host,String ip){
        long before=System.nanoTime();Status stage=Status.TCP_FAILED;Socket raw=new Socket(Proxy.NO_PROXY);SSLSocket secure=null;
        try{
            sockets.add(raw);if(ended.get() || !connector.current())throw new IOException("Cancelled");
            connector.connect(raw,IpCandidates.address(ip),443,1500);raw.setSoTimeout(1800);stage=Status.TLS_FAILED;
            if(ended.get() || !connector.current())throw new IOException("Cancelled");
            secure=(SSLSocket)tls.createSocket(raw,host,443,true);sockets.add(secure);secure.setSoTimeout(1800);
            SSLParameters parameters=secure.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");
            parameters.setServerNames(Collections.singletonList(new SNIHostName(host)));secure.setSSLParameters(parameters);
            List<String> modern=new ArrayList<>();for(String protocol:secure.getSupportedProtocols())if(protocol.equals("TLSv1.2") || protocol.equals("TLSv1.3"))modern.add(protocol);
            secure.setEnabledProtocols(modern.toArray(new String[0]));secure.startHandshake();stage=Status.TLS_NO_HTTP;
            if(ended.get() || !connector.current())throw new IOException("Cancelled");
            secure.getOutputStream().write(("HEAD / HTTP/1.1\r\nHost: "+host+"\r\nUser-Agent: DarsBridge-IP-Check/0.13\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            secure.getOutputStream().flush();String line=line(secure.getInputStream());
            if(!line.matches("HTTP/1\\.[01] [1-5][0-9]{2}(?: .*)?"))throw new IOException("Invalid HTTP status");
            return new Result(ip,Status.HTTPS_RESPONSE,Integer.parseInt(line.substring(9,12)),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before));
        }catch(IOException | RuntimeException e){return new Result(ip,stage,0,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before));}
        finally{if(secure!=null){sockets.remove(secure);try{secure.close();}catch(IOException ignored){}}sockets.remove(raw);try{raw.close();}catch(IOException ignored){}}
    }
    private static String line(InputStream in)throws IOException{
        StringBuilder out=new StringBuilder();for(int i=0;i<512;i++){int b=in.read();if(b<0)throw new EOFException();if(b=='\n'){if(out.length()>0 && out.charAt(out.length()-1)=='\r')out.setLength(out.length()-1);return out.toString();}out.append((char)b);}throw new IOException("Status too long");
    }
    private void finish(End why){
        if(!ended.compareAndSet(false,true))return;
        for(Socket socket:sockets)try{socket.close();}catch(IOException ignored){}
        workers.shutdownNow();coordinator.shutdownNow();listener.finished(why,completed,total);
    }
    @Override public void close(){finish(End.CANCELLED);}
}
