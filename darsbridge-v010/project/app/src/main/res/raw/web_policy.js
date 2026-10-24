/* Runs before page scripts in matching documents. No native message bridge is exposed.
 * This is defense in depth. Unusual realms, engine bugs and non-WebRTC traffic need
 * separate measurement; this does not make WebView equivalent to Tor Browser.
 */
(function () {
    'use strict';
    var names = ['RTCPeerConnection', 'webkitRTCPeerConnection', 'mozRTCPeerConnection',
        'RTCIceGatherer', 'RTCIceTransport', 'RTCDtlsTransport'];
    names.forEach(function (name) {
        try {
            Object.defineProperty(window, name, {
                value: undefined, configurable: false, writable: false
            });
        } catch (ignored) { }
    });
})();
