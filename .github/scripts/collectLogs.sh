#!/bin/bash
echo "Collecting test reports"
mkdir -p artifacts/test-reports
cp -r /tmp/testlogs artifacts
for i in `find . -name "TEST-*.xml"`
do
    echo "Current file: ${1}"
    cp ${i} artifacts/test-reports
done