"""Durable partner simulator. Each reservation is bound to one student/course payload.
Holds do not expire automatically: the home university must confirm or cancel them.
This explicit contract lets recovery safely confirm after a prolonged outage.
"""
import sqlite3

class Store:
    def __init__(self, path, capacity=120):
        self.path, self.capacity = path, capacity
        with self.connect() as db:
            db.execute('PRAGMA journal_mode=WAL')
            db.execute('CREATE TABLE IF NOT EXISTS reservations (id TEXT PRIMARY KEY, student INTEGER NOT NULL, course INTEGER NOT NULL, state TEXT NOT NULL)')

    def connect(self):
        return sqlite3.connect(self.path, timeout=10)

    def apply(self, id_, student, course, action):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row=db.execute('SELECT student,course,state FROM reservations WHERE id=?',(id_,)).fetchone()
            if row and row[:2] != (student,course):
                raise ValueError('IDEMPOTENCY_KEY_REUSED')
            state=row[2] if row else 'ABSENT'
            if action == 'reserve' and state == 'ABSENT':
                occupied=db.execute("SELECT COUNT(*) FROM reservations WHERE course=? AND state IN ('HELD','CONFIRMED')",(course,)).fetchone()[0]
                # Different request keys for the same student/course cannot allocate two seats.
                duplicate=db.execute("SELECT 1 FROM reservations WHERE student=? AND course=? AND state IN ('HELD','CONFIRMED')",(student,course)).fetchone()
                state='FULL' if occupied>=self.capacity or duplicate else 'HELD'
            elif action == 'confirm' and state == 'HELD':
                state='CONFIRMED'
            elif action == 'cancel' and state != 'CONFIRMED':
                # A tombstone also blocks a delayed reserve that arrives after cancellation.
                state='CANCELLED'
            if action and state != 'ABSENT':
                db.execute('INSERT INTO reservations VALUES (?,?,?,?) ON CONFLICT(id) DO UPDATE SET state=excluded.state',(id_,student,course,state))
            return state
