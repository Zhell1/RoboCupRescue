#! /bin/bash
trap "echo 'killing...';./kill.sh;exit" INT
. functions.sh

processArgs $*


#save the score in a special log file (not in log/ directory to not remove it)
cat logs/kernel.log | grep -a "Score" | tail -n 2 | head -n 1 | cut -d ' ' -f 5 >> ../../../RobotRescue/score_log_thomas

# Delete old logs
rm -f $LOGDIR/*.log
	sh kill.sh

#startGIS
startKernel --nomenu
startSims --nogui

echo "Start your agents"
waitFor $LOGDIR/kernel.log "Kernel has shut down" 30



kill $PIDS
./kill.sh
