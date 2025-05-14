#!/usr/bin/env bash
CDIR="core/target/dist-core"
#PLUGIN_F=$(find j2cc-maven-plugin/target/ -maxdepth 1 -mindepth 1 -type f -name "j2cc-maven-plugin-*.jar")

rm -rfv dist_package dist.tar.gz
mkdir dist_package
cp -r "$CDIR"/* "dist_package/"
#cp "$CORE_F" dist_package
#cp "$PLUGIN_F" dist_package
cp -r util dist_package
cp -r natives dist_package
rm -rv dist_package/util/.idea
rm -rv dist_package/util/cmake-build-*
rm -v dist_package/util/CMakeLists.txt
tar cvf dist.tar.gz dist_package