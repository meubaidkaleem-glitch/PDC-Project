DistriCompute @VERSION@ for Linux (x86-64)
==========================================

Runs computations sequentially, on several threads, or on workers, measures them and compares the strategies.
Everything is included: a Java runtime, the application, its database and two local workers. Nothing has to be
installed.

Start
-----
    tar -xzf DistriCompute-@VERSION@-linux-x64.tar.gz
    DistriCompute-@VERSION@-linux-x64/districompute.sh

Your browser shows DistriCompute after a few seconds (otherwise open the address printed in the terminal). Sign in
with the user and password printed there ("First sign-in") and change the password in Settings. Press Ctrl+C to stop.

Your data: ~/.local/share/districompute (or $XDG_DATA_HOME/districompute). It stays when you replace this folder.

Settings: DISTRICOMPUTE_HTTP_PORT (default 8080), DISTRICOMPUTE_DATA_DIR.

DistriCompute listens only on this computer (127.0.0.1). For several users or worker computers, use the server
installation with MySQL and Docker described at https://github.com/meubaidkaleem-glitch/PDC-Project

Included software: Eclipse Temurin @JAVA_VERSION@ (runtime/, licence in runtime/legal and runtime/NOTICE),
DistriCompute-Core and DistriCompute-Distributed (inside app/districompute.jar).
