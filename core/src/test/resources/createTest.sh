#!/usr/bin/env bash

NAME="$1"
TP="$2"
if [[ "$TP" == "" ]]; then
  TP="ASSEMBLY"
fi
echo "Creating test $NAME"

mkdir -p "tests/$NAME"

if [[ "$TP" == "ASSEMBLY" ]]; then
  touch "tests/$NAME/$NAME.jasm"
else
  cat > "tests/$NAME/$NAME.java" << EOF
public class Test {
	public static void main(String[] args) {

	}
}
EOF
fi

touch "tests/$NAME/$NAME.output"
cat > "tests/$NAME/$NAME.test.json" << EOF
{
  "type": "$TP",
  "name": "Unnamed Test",
  "expectedProgramExit": 0,
  "programArgs": null,
  "jvmArgs": null
}
EOF
echo "OK"