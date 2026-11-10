"""Deployment-supplied identity keys and vehicle grants; no client ownership flags."""
import json
import threading
from dataclasses import dataclass
from pathlib import Path

class AccessDenied(Exception):
    pass

class AccessUnavailable(Exception):
    pass

class ReviewDenied(Exception):
    pass

@dataclass(frozen=True)
class Principal:
    subject: str
    vehicles: frozenset
    scopes: frozenset

class AccessPolicy:
    def __init__(self, issuer, audience, keys, grants):
        import jwt
        from cryptography.hazmat.primitives.serialization import load_pem_public_key
        from cryptography.hazmat.primitives.asymmetric.rsa import RSAPublicKey
        if not isinstance(issuer,str) or not issuer.startswith('https://') or not isinstance(audience,str) or not audience:
            raise ValueError('HTTPS issuer and audience required')
        if not isinstance(keys,dict) or not keys or not isinstance(grants,dict):
            raise ValueError('trusted public keys and vehicle grants required')
        self.keys={}
        for kid,pem in keys.items():
            if not isinstance(kid,str) or not kid:raise ValueError('key id required')
            key=load_pem_public_key(pem.encode())
            if not isinstance(key,RSAPublicKey) or key.key_size<2048:raise ValueError('RSA 2048+ public key required')
            self.keys[kid]=key
        self.grants={}
        for subject,vehicles in grants.items():
            if not isinstance(subject,str) or not subject or not isinstance(vehicles,list):raise ValueError('invalid grant')
            if any(not isinstance(v,str) or not v for v in vehicles):raise ValueError('invalid vehicle')
            self.grants[subject]=frozenset(vehicles)
        self.jwt=jwt;self.issuer=issuer;self.audience=audience

    @classmethod
    def from_file(cls,path):
        return FileAccessPolicy(path)

    def authorized_subjects(self, vehicle):
        return frozenset(subject for subject, vehicles in self.grants.items() if vehicle in vehicles)

    def principal(self,authorization):
        if not isinstance(authorization,str) or not authorization.startswith('Bearer '):raise AccessDenied()
        token=authorization[7:]
        if not token or len(token)>16384 or any(c.isspace() for c in token):raise AccessDenied()
        try:
            header=self.jwt.get_unverified_header(token)
            if header.get('alg')!='RS256' or header.get('crit'):raise AccessDenied()
            key=self.keys[header['kid']]
            claims=self.jwt.decode(token,key,algorithms=['RS256'],issuer=self.issuer,audience=self.audience,
                options={'require':['exp','iat','nbf','sub','iss','aud'],'strict_aud':True})
            if not isinstance(claims['sub'],str) or not claims['sub'] or len(claims['sub'])>512:raise AccessDenied()
            for name in ('exp','iat','nbf'):
                if type(claims[name]) is not int:raise AccessDenied()
            if not 0<claims['exp']-claims['iat']<=900 or claims['nbf']>=claims['exp']:raise AccessDenied()
            if not isinstance(claims.get('scope'),str) or 'events:read' not in claims['scope'].split():raise AccessDenied()
            # Vehicle claims and authenticated/ownership_verified flags are never authority.
            return Principal(claims['sub'],self.grants.get(claims['sub'],frozenset()),frozenset(claims['scope'].split()))
        except (self.jwt.PyJWTError,KeyError,TypeError,ValueError) as exc:
            raise AccessDenied() from exc

    def vehicles(self,authorization):return self.principal(authorization).vehicles

    def reviewer(self,authorization):
        principal=self.principal(authorization)
        if 'events:review' not in principal.scopes:raise ReviewDenied()
        return principal

class FileAccessPolicy:
    """Re-read trusted deployment configuration before each request, including revocations."""
    def __init__(self,path):
        self.path=Path(path)
        self.lock=threading.Lock()
        self.raw=None
        self.policy=None
        self._current() # Invalid initial configuration prevents startup.

    def _current(self):
        with self.lock:
            try:
                with self.path.open('rb') as source:raw=source.read(1024*1024+1)
                if len(raw)>1024*1024:raise ValueError('access configuration too large')
                if raw!=self.raw:
                    config=json.loads(raw)
                    policy=AccessPolicy(config['issuer'],config['audience'],config['public_keys'],config['vehicle_grants'])
                    self.policy=policy
                    self.raw=raw
                return self.policy
            except (OSError,ValueError,TypeError,KeyError,AttributeError) as exc:
                # Retaining the last good configuration must never retain revoked access.
                raise AccessUnavailable('access configuration unavailable') from exc

    def vehicles(self,authorization):
        return self._current().vehicles(authorization)

    def principal(self,authorization):return self._current().principal(authorization)

    def reviewer(self,authorization):return self._current().reviewer(authorization)

    def authorized_subjects(self,vehicle):
        return self._current().authorized_subjects(vehicle)
