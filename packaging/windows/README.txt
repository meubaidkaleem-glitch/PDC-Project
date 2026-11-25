DistriCompute @VERSION@ for Windows (64-bit)
=============================================

Runs computations sequentially, on several threads, or on workers, measures them and compares the strategies.
Everything is included: a Java runtime, the application, its database and two local workers. Nothing has to be
installed, and nothing is changed outside this folder and your data folder.

Start
-----
1. Unzip this folder anywhere (for example to your Desktop or Documents).
2. Double-click DistriCompute.cmd. A window opens and your browser shows DistriCompute after a few seconds.
   If Windows SmartScreen says "Windows protected your PC", choose "More info" and "Run anyway"
   (the launcher is a plain text script; open it in Notepad to read it).
3. Sign in with the user and password printed in the window ("First sign-in"). Change the password in Settings.

Keep the window open while you use DistriCompute. Close it, or press Ctrl+C in it, to stop.
Double-clicking DistriCompute.cmd again while it runs only opens the browser.

Where things are
----------------
Your data (database, results, uploads, generated passwords): %LOCALAPPDATA%\DistriCompute\data
It stays when you delete or replace this folder; to upgrade, unzip the new version and start it.
To remove DistriCompute completely, delete this folder and the data folder.

Settings (optional, set before starting, for example in a command prompt)
-------------------------------------------------------------------------
DISTRICOMPUTE_HTTP_PORT   web port (default 8080), if another program uses 8080
DISTRICOMPUTE_DATA_DIR    another data folder
DISTRICOMPUTE_NO_BROWSER  set to 1 to not open the browser

DistriCompute listens only on this computer (127.0.0.1). For several users or worker computers, use the server
installation with MySQL and Docker described at
https://github.com/meubaidkaleem-glitch/PDC-Project

Included software: Eclipse Temurin @JAVA_VERSION@ (runtime\, licence in runtime\legal and runtime\NOTICE),
DistriCompute-Core and DistriCompute-Distributed (inside app\districompute.jar).
