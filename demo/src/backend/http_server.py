"""Bound concurrent local HTTP requests and idle socket waits."""
import threading
from http.server import ThreadingHTTPServer


class BoundedHTTPServer(ThreadingHTTPServer):
    def __init__(self, address, handler, *, max_connections=32, idle_timeout=15):
        if type(max_connections) is not int or not 1 <= max_connections <= 256:
            raise ValueError('max_connections must be between 1 and 256')
        if not isinstance(idle_timeout, (int, float)) or not 0 < idle_timeout <= 60:
            raise ValueError('idle_timeout must be between 0 and 60 seconds')
        self._slots = threading.BoundedSemaphore(max_connections)
        self._idle_timeout = idle_timeout
        super().__init__(address, handler)

    def get_request(self):
        request, address = super().get_request()
        request.settimeout(self._idle_timeout)
        return request, address

    def process_request(self, request, address):
        if not self._slots.acquire(blocking=False):
            try:
                request.settimeout(0.25)
                request.sendall(b'HTTP/1.1 503 Service Unavailable\r\n'
                                b'Content-Length: 0\r\nConnection: close\r\n'
                                b'Cache-Control: no-store\r\nRetry-After: 1\r\n\r\n')
            except OSError:
                pass
            finally:
                self.shutdown_request(request)
            return
        try:
            super().process_request(request, address)
        except Exception:
            self._slots.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self._slots.release()
