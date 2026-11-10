"""Trusted deployment bindings for one notification device per vehicle.

Bindings are operator-managed, not client ownership claims. Recipient kinds are explicit.
"""
import json
from pathlib import Path


class FileRecipientResolver:
    def __init__(self, access_policy, path, prefix='synthetic-device:'):
        if prefix not in ('synthetic-device:', 'relay-device:'):
            raise ValueError('unsupported recipient kind')
        self.access_policy = access_policy
        self.path = Path(path)
        self.prefix = prefix

    def __call__(self, vehicle):
        with self.path.open('rb') as source:
            raw = source.read(1024 * 1024 + 1)
        if len(raw) > 1024 * 1024:
            raise ValueError('recipient registry too large')
        bindings = json.loads(raw)
        if not isinstance(bindings, dict):
            raise ValueError('recipient registry must be an object')
        binding = bindings.get(vehicle)
        if binding is None:
            return None
        if (not isinstance(binding, dict) or set(binding) != {'subject', 'recipient', 'enabled'}
                or not isinstance(binding['subject'], str) or not binding['subject']
                or not isinstance(binding['recipient'], str)
                or not binding['recipient'].startswith(self.prefix)
                or not len(self.prefix) < len(binding['recipient']) <= 512
                or any(c.isspace() for c in binding['recipient'])
                or type(binding['enabled']) is not bool):
            raise ValueError('invalid recipient binding')
        if not binding['enabled']:
            return None
        # Reload grants after reading the device binding. Never retain last-good authorization.
        if binding['subject'] not in self.access_policy.authorized_subjects(vehicle):
            return None
        return binding['recipient']
