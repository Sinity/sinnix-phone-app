"""Exercise the app's actual supervisor predicate against isolated processes."""

import os
import re
import shlex
import subprocess
import sys
from pathlib import Path

source = (Path(__file__).resolve().parents[1] / "app/src/main/kotlin/dev/sinnix/phone/prime/TransportGuard.kt").read_text()
match = re.search(r'"(pgrep [^"]+) >/dev/null', source)
assert match, "transport supervisor predicate not found"
predicate = shlex.split(match[1]) + ["-P", str(os.getpid())]


def exercise(real_supervisor):
    code = "import ctypes,time; "
    if real_supervisor:
        code += "assert ctypes.CDLL(None).prctl(15, b'runsvdir', 0, 0, 0) == 0; "
    code += "print('ready',flush=True); time.sleep(30)"
    # The false control has the name in argv, just like the shell which runs
    # TransportGuard's repair script, but it is not a supervisor process.
    with subprocess.Popen([sys.executable, "-c", code, "runsvdir"], stdout=subprocess.PIPE, text=True) as child:
        try:
            assert child.stdout.readline().strip() == "ready"
            result = subprocess.run(predicate, capture_output=True, text=True, timeout=5)
            assert result.returncode == (0 if real_supervisor else 1), result.stdout
        finally:
            child.terminate()
            child.wait(timeout=5)


exercise(False)
exercise(True)
print("Transport supervisor predicate: command-line false positive refused; actual process detected")
