package com.codex.splashskip;

/** Exercise replies retained by SystemUI across endpoint changes and process restarts. */
public final class PairingSessionCheck {
    private static int checks;
    private static void check(boolean value) {
        ++checks;
        if (!value) throw new AssertionError("Pairing session check " + checks);
    }
    public static void main(String[] args) {
        PairingSession session = new PairingSession();
        check(!session.accepts(42081, "old", 10));
        session.begin(100);
        check(session.update(42081, 101));
        String first = session.token();
        check(session.accepts(42081, first, 102));
        check(!session.accepts(33647, first, 102));
        check(!session.accepts(42081, null, 102));
        check(!session.update(42081, 103));
        check(first.equals(session.token()));
        check(session.update(33647, 104));
        String second = session.token();
        check(!session.accepts(42081, first, 105));
        check(!session.accepts(33647, first, 105));
        check(session.accepts(33647, second, 105));
        session.lost();
        check(!session.accepts(33647, second, 106));
        check(session.update(33647, 107));
        check(!session.accepts(33647, second, 108));
        check(session.accepts(33647, session.token(), 180099));
        check(!session.accepts(33647, session.token(), 180100));
        check(!session.update(42081, 180100));
        session.begin(200000);
        check(session.update(33647, 200001));
        check(!session.accepts(33647, second, 200002));
        check(!new PairingSession().accepts(33647, session.token(), 200002));
        session.clear();
        check(!session.update(33647, 200003));
        check(!session.update(0, 200003));
        check(!session.update(65536, 200003));
        System.out.println("Pairing session checks passed: " + checks);
    }
}
