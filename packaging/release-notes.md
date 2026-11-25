DistriCompute @VERSION@: run, measure and compare sequential, multithreaded and distributed computations from your
browser. This release packages the application branch `feature/application-gui` at @COMMIT@ with the pinned
DistriCompute-Core and DistriCompute-Distributed.

## Install on Windows (nothing else needed)

1. Download **DistriCompute-@VERSION@-windows-x64.zip** below and unzip it (right-click, *Extract All*).
2. Open the folder and double-click **DistriCompute.cmd**. If SmartScreen says "Windows protected your PC", choose
   *More info*, then *Run anyway*.
3. Your browser opens DistriCompute. Sign in with the user and password shown in the DistriCompute window, then
   change the password in Settings.

Keep the window open while you use it; close it to stop. Java, a database and the workers are included and run only
on your computer (127.0.0.1). Your data is kept in `%LOCALAPPDATA%\DistriCompute\data`.

## Install on Linux (x86-64)

```bash
tar -xzf DistriCompute-@VERSION@-linux-x64.tar.gz
```

```bash
DistriCompute-@VERSION@-linux-x64/districompute.sh
```

## What was tested

Before this release was published, CI unpacked the Windows package on a Windows Server runner with Java removed
from `PATH` and `JAVA_HOME`, started it from a folder whose name contains a space, checked that it listens only on
127.0.0.1, signed in with the printed password, ran a distributed matrix multiplication on the two local workers to
a verified result, started it a second time (it only reports the running instance), stopped it forcibly and checked
that the data survived a restart. The Linux package passed the same test in an Ubuntu container without Java.
Workflow run: @RUN_URL@

For several users and worker machines use the server installation (MySQL, Docker Compose): see the
[README](https://github.com/meubaidkaleem-glitch/PDC-Project#readme).

## Checksums (SHA-256)

