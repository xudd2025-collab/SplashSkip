package com.codex.splashskip;

interface ISensorGuard {
    String protect(String packageName, int userId) = 1;
    String getState(String packageName, int userId) = 2;
    void restoreAll() = 3;
    void destroy() = 16777114;
}
