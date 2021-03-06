#!/bin/bash

# Some useful directories
DIR=$(dirname "$0")
BASE="$DIR/.."
CONFIG=$BASE/config

# Read the settings
source "$CONFIG/settings.sh" 

# Convert app name to lowercase
APPLC=$(echo $APP | tr "[:upper:]" "[:lower:]")

URI="$jobserver:8090/jobs?context=$APPLC&appName=$APPLC&classPath=$CP.statistics&sync=true"

curl -d $'' $URI 
