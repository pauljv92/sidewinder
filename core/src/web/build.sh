#!/bin/bash
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
echo "Script location: $DIR"
mkdir -p ./target
cp -r $DIR target/web_temp/
cd target/web_temp
polymer install
DIR=$(pwd)
polymer build --add-service-worker --js-compile --js-minify --css-minify --html-minify --bundle --root $DIR
mv build ../build 