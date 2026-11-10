"""Bound socket sends by elapsed time, even when a peer keeps making progress."""
import time
from contextlib import contextmanager


@contextmanager
def transfer_deadline(connection, seconds, clock=time.monotonic):
    previous = connection.gettimeout()
    deadline = clock() + seconds

    def check():
        remaining = deadline - clock()
        if remaining <= 0:
            raise TimeoutError('download transfer deadline exceeded')
        connection.settimeout(min(previous, remaining) if previous is not None else remaining)

    try:
        check()
        yield check
    finally:
        connection.settimeout(previous)
