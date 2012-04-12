gitV=$(git describe --always --dirty)
$(which time) -f "$gitV;%e;%U;%S;%P" ./bin/fb -Dprofiler.report=true analyze "$@"
