package com.agentkosticka.easierspot.privileged;

/** Minimal shell-identity bridge hosted by a Shizuku UserService. */
interface IPrivilegedShell {
    String execute(in String[] command, long timeoutMillis);
    void destroy();
}
