"""HTTPS relay contract for OEM-selected push infrastructure; not a direct device provider."""
import http.client
import json
import re
import ssl
from pathlib import Path
from urllib.parse import urlsplit
from uuid import UUID


class HttpsNotificationRelay:
    mode = 'relay'

    def __init__(self, endpoint, token, connection_factory=None):
        url = urlsplit(endpoint)
        if (url.scheme != 'https' or not url.hostname or url.username or url.password
                or url.query or url.fragment or not re.fullmatch(r'/[A-Za-z0-9_/-]+', url.path)
                or any(c.isspace() for c in endpoint)):
            raise ValueError('invalid relay endpoint')
        if not isinstance(token, str) or not re.fullmatch(r'[A-Za-z0-9._~+/-]{16,4096}=*', token):
            raise ValueError('invalid relay credential')
        self.host, self.port, self.path = url.hostname, url.port or 443, url.path
        self._token = token
        self._connection = connection_factory or (lambda: http.client.HTTPSConnection(
            self.host, self.port, timeout=5, context=ssl.create_default_context()))

    @classmethod
    def from_file(cls, path):
        with Path(path).open('rb') as stream:
            raw = stream.read(16385)
        if len(raw) > 16384:
            raise ValueError('relay configuration too large')
        config = json.loads(raw)
        if not isinstance(config, dict) or set(config) != {'endpoint', 'token'}:
            raise ValueError('invalid relay configuration')
        return cls(config['endpoint'], config['token'])

    def send(self, recipient, payload, idempotency_key):
        if (not isinstance(recipient, str) or not re.fullmatch(r'relay-device:[A-Za-z0-9._~-]{1,480}', recipient)
                or not re.fullmatch(r'[0-9a-f]{64}', idempotency_key)
                or set(payload) != {'event_id', 'source', 'type'}
                or payload['source'] != 'synthetic' or payload['type'] != 'event_available'
                or str(UUID(payload['event_id'])) != payload['event_id']):
            raise ValueError('invalid relay notification')
        body = json.dumps({'schema': 'road-guardian.push-relay.v1', 'recipient': recipient,
                           'notification': payload}, separators=(',', ':')).encode()
        connection = self._connection()
        try:
            connection.request('POST', self.path, body, {
                'Authorization': 'Bearer ' + self._token, 'Content-Type': 'application/json',
                'Accept': 'application/json', 'Idempotency-Key': idempotency_key})
            response = connection.getresponse()
            if response.status not in (200, 202):
                return False
            if response.getheader('Content-Type', '').split(';', 1)[0].strip().lower() != 'application/json':
                return False
            raw = response.read(8193)
            if len(raw) > 8192:
                return False
            try:
                receipt = json.loads(raw)
            except (ValueError, UnicodeError):
                return False
            return (isinstance(receipt, dict) and receipt.get('accepted') is True
                    and receipt.get('idempotency_key') == idempotency_key)
        finally:
            connection.close()
