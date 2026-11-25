# Standalone installation (Windows and Linux)

The easiest way to use DistriCompute on one computer: download one file, unpack it, double-click. The package
contains everything: a Java runtime, the application with its interface, an embedded database and two workers.
Nothing has to be installed, no administrator rights are needed, and nothing listens beyond the computer.

For several users or worker machines, use the server installation instead ([deployment.md](deployment.md)).

## 1. Install and start

Packages are attached to the GitHub releases of the repository:
<https://github.com/meubaidkaleem-glitch/PDC-Project/releases>

### Windows 10 or 11 (64-bit)

1. Download `DistriCompute-<version>-windows-x64.zip` and unzip it (right-click, *Extract All*) anywhere, for example
   to Documents.
2. Double-click `DistriCompute.cmd` in the unzipped folder. If SmartScreen says "Windows protected your PC", choose
   *More info*, then *Run anyway* (the launcher is a short text script; open it in Notepad to read it).
3. A window opens; after a few seconds the browser shows DistriCompute. The window shows the address and, at the
   first start, the user and password to sign in with:

   ```
   ============================================================================
     DistriCompute is running at http://localhost:8080/
     First sign-in:  user admin   password <generated>
     Change the password in Settings after signing in.
     Data: C:\Users\<you>\AppData\Local\DistriCompute\data
     Keep this window open while you use DistriCompute; close it or press Ctrl+C to stop.
   ============================================================================
   ```

4. Sign in and change the password in Settings. Once changed, the window no longer shows a password.

Keep the window open while you use DistriCompute; close it to stop. Double-clicking `DistriCompute.cmd` while it is
running only opens the browser.

### Linux (x86-64)

```bash
tar -xzf DistriCompute-<version>-linux-x64.tar.gz
```

```bash
DistriCompute-<version>-linux-x64/districompute.sh
```

The terminal shows the same notice; the browser opens with `xdg-open` when there is a desktop. Ctrl+C stops it.

## 2. What is in the package

| Path | Content |
|---|---|
| `DistriCompute.cmd` / `districompute.sh` | The launcher: starts the bundled Java with the standalone profile and the data folder |
| `runtime/` | Eclipse Temurin 21 JRE (licence in `runtime/legal` and `runtime/NOTICE`) |
| `app/districompute.jar` | The application with its interface, DistriCompute-Core and DistriCompute-Distributed |
| `README.txt` | Short instructions |

The launcher uses only `runtime/`: a Java installed on the computer, `JAVA_HOME` and `PATH` are ignored.

## 3. Your data

| System | Data folder |
|---|---|
| Windows | `%LOCALAPPDATA%\DistriCompute\data` |
| Linux | `$XDG_DATA_HOME/districompute`, by default `~/.local/share/districompute` |

It holds the database (`database/`), results and uploads, the generated initial password and the workers' secret.
It is outside the package folder, so a new version can be unzipped and started with the same data, and deleting the
package folder keeps the data. To remove DistriCompute completely, delete the package folder and the data folder.

Windows gives the data folder to the signed-in user (and administrators of the computer), the usual permissions of
`AppData\Local`; on Linux the secret files are created readable by their owner only.

## 4. Settings

Environment variables read by the launcher:

| Variable | Default | Use |
|---|---|---|
| `DISTRICOMPUTE_HTTP_PORT` | `8080` | Web port, if another program already uses 8080 |
| `DISTRICOMPUTE_DATA_DIR` | see section 3 | Another data folder |
| `DISTRICOMPUTE_NO_BROWSER` | not set | Set to `1` to never open the browser (for example on a machine without a desktop) |

Options after the launcher's name are passed to the application, for example four local workers with two slots each:

```bash
districompute.sh --districompute.standalone.local-workers=4 --districompute.standalone.local-worker-slots=2
```

On Windows the same works from a command prompt: `DistriCompute.cmd --districompute.standalone.local-workers=4`.
Every setting of [configuration.md](configuration.md) can be given this way.

## 5. How it differs from the server installation

The standalone mode is the Spring profile `standalone` (`backend/src/main/resources/application-standalone.yml`):

| | Standalone | Server |
|---|---|---|
| Database | Embedded H2 file in the data folder, schema from `database/migrations-h2` | MySQL 8.4, `database/migrations` |
| Workers | Two workers started inside the application (`local-1`, `local-2`), connected to its coordinator over loopback TCP with the cluster secret, as separate processes would be | Separate worker processes on any machine |
| Network | Web port and worker port bound to 127.0.0.1; the worker port is any free port | Configurable; workers connect from other machines |
| Fault lab | Enabled (its simulated workers run on an isolated coordinator inside the process) | Disabled unless configured |
| Console | Address and initial password notice; warnings and the application's messages only | Normal logging; the initial password only in its file |

Everything else is the same application: Core's algorithms and verification, Distributed's coordinator, scheduler
and failure handling, the same pages and API. The integration tests run once against MySQL and once with the
standalone profile against H2 ([testing.md](testing.md)).

**Measurements:** the local workers share the computer with the application, so a distributed run measures the
coordination of workers over loopback TCP on one machine, not a cluster. It is usually slower than a multithreaded
run of the same task; the performance analysis shows this as measured. Use the server installation with workers on
other machines to measure a real cluster.

## 6. Troubleshooting

| Problem | What to do |
|---|---|
| The window closes or shows "stopped with an error" | Read the messages above it. "Port 8080 was already in use": set `DISTRICOMPUTE_HTTP_PORT` to another port |
| The browser does not open | Open the address shown in the window yourself |
| SmartScreen or an antivirus blocks `DistriCompute.cmd` or `java.exe` | Allow it; both come from the package (`runtime\bin\java.exe` is Eclipse Temurin) |
| Forgotten password | While it was never changed, the window shows it, and it is in `initial-admin-password` in the data folder. A changed password cannot be recovered: stop DistriCompute and delete the data folder to start over (this deletes all experiments) |
| Another user on the same computer | Each Windows or Linux user has their own data folder and administrator; only one instance can use port 8080 at a time |

## 7. Building and releasing the packages

```bash
scripts/build.sh --skip-tests
```

```bash
scripts/package-standalone.sh --version 0.1.0 --skip-build
```

`scripts/package-standalone.sh` downloads the Temurin runtimes for Windows and Linux once into `dist/cache`, checks
them against the SHA-256 checksums pinned in the script (Adoptium's published values), and writes
`dist/DistriCompute-<version>-windows-x64.zip`, `dist/DistriCompute-<version>-linux-x64.tar.gz` and `dist/SHA256SUMS`.
Updating the runtime means changing its version and both checksums in the script.

Tests of a package (section 6 of [testing.md](testing.md)):

```bash
STANDALONE_SMOKE_IMAGE=ubuntu:24.04 scripts/standalone-smoke.sh dist/DistriCompute-0.1.0-linux-x64.tar.gz
```

```powershell
pwsh -File scripts\standalone-smoke.ps1 -Archive dist\DistriCompute-0.1.0-windows-x64.zip
```

The workflow `.github/workflows/standalone.yml` builds both packages for every push to `feature/application-gui`,
tests the Linux package in an Ubuntu container without Java and the Windows package on a Windows runner with Java
removed from `PATH` and `JAVA_HOME`, and keeps them as workflow artifacts. Pushing a tag `v<version>` (for example
`v0.1.0-preview.1`) additionally publishes the tested packages as a GitHub release; a version with a hyphen is
marked as a pre-release.
