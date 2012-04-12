out=execLog-JDK-noTee-`timestamp`
./profileFB.sh /usr/lib/jvm/jre-1.6.0-openjdk.x86_64/lib/rt.jar > $out 2>&1
tail -1 $out >> FB-JDK.csv
