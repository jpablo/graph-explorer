#!/bin/bash

if [ ! -f cs ]; then
  curl -fL "https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-linux.gz" | gzip -d > cs
  chmod +x cs
fi

export PATH="$PATH:/opt/buildhome/.local/share/coursier/bin"

if ! command -v stc &> /dev/null; then
  ./cs install stc
fi

stc --ignoredLibs node @codemirror/view @codemirror/lang-javascript @viz-js/viz @codemirror/commands jsdom @viz-js/lang-dot codemirror