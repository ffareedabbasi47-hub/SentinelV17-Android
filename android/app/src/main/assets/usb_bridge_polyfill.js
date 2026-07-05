// ─────────────────────────────────────────────────────────────────
// ANDROID USB OTG POLYFILL — injected at runtime by MainActivity,
// never written into index.html. Only activates when:
//   (a) navigator.serial does NOT already exist (true on Android WebView), AND
//   (b) window.AndroidUSB native bridge is present (true only inside this APK)
// On desktop Chrome / any browser with real Web Serial, this is a no-op,
// so the ORIGINAL app behaves 100% identically there.
//
// It reproduces the exact Web Serial API shape the app already calls:
//   navigator.serial.requestPort() -> port
//   port.open({ baudRate })
//   port.writable.getWriter().write(Uint8Array)
//   port.readable.getReader().read() -> { value: Uint8Array, done }
//   port.close()
// so readSerial()/sendSerial()/disconnectCleanup() in index.html run UNCHANGED.
// ─────────────────────────────────────────────────────────────────
(function () {
    if (window.navigator.serial) return;      // real Web Serial available — don't touch it
    if (!window.AndroidUSB) return;           // not running inside the Android bridge

    var _controller = null;
    var _connectResolvers = [];

    // Native -> JS: called after AndroidUSB.requestConnect() resolves (success or failure)
    window.__sentinelUsbOnConnect = function (success, message) {
        var pending = _connectResolvers.splice(0);
        pending.forEach(function (p) {
            if (success) p.resolve();
            else p.reject(new Error(message || 'USB OTG connection failed'));
        });
    };

    // Native -> JS: called for every chunk of bytes read off the USB device
    window.__sentinelUsbOnData = function (base64) {
        if (!_controller) return;
        try {
            var binStr = atob(base64);
            var bytes = new Uint8Array(binStr.length);
            for (var i = 0; i < binStr.length; i++) bytes[i] = binStr.charCodeAt(i);
            _controller.enqueue(bytes);
        } catch (e) { /* ignore malformed chunk */ }
    };

    // Native -> JS: called when the USB device is unplugged/detached.
    // Errors the stream (not close()) so reader.read() REJECTS — matching
    // real Web Serial behavior on physical disconnect — which is exactly
    // what index.html's readSerial() catch(e){ disconnectCleanup(); } expects.
    window.__sentinelUsbOnDisconnect = function (message) {
        if (_controller) {
            try { _controller.error(new Error(message || 'USB device disconnected')); } catch (e) {}
            _controller = null;
        }
    };

    var fakePort = {
        readable: null,
        writable: null,
        open: function (options) {
            var self = this;
            return new Promise(function (resolve, reject) {
                _connectResolvers.push({ resolve: resolve, reject: reject });
                try {
                    window.AndroidUSB.requestConnect();
                } catch (e) {
                    _connectResolvers.pop();
                    reject(e);
                }
            }).then(function () {
                self.readable = new ReadableStream({
                    start: function (controller) { _controller = controller; },
                    cancel: function () { _controller = null; }
                });
                self.writable = new WritableStream({
                    write: function (chunk) {
                        var binary = '';
                        for (var i = 0; i < chunk.length; i++) binary += String.fromCharCode(chunk[i]);
                        window.AndroidUSB.write(btoa(binary));
                        return Promise.resolve();
                    }
                });
            });
        },
        close: function () {
            try { window.AndroidUSB.disconnect(); } catch (e) {}
            return Promise.resolve();
        }
    };

    window.navigator.serial = {
        requestPort: function () { return Promise.resolve(fakePort); },
        getPorts: function () { return Promise.resolve([]); }
    };
})();
