package com.codex.splashskip;

/** A missing window root is not a foreign overlay if its ID matches the validated active app root. */
final class WindowOwnershipPolicy {
    static boolean target(String expected,String owner,int windowId,int validatedActiveId) {
        return expected.equals(owner) || validatedActiveId>=0 && windowId==validatedActiveId;
    }
}
