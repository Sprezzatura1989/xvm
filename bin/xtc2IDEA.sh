#!/bin/bash

# install the .x source code syntax highlighting into the IntelliJ IDEA

binDir=$(dirname "$BASH_SOURCE")
ideaDir=~/Library/Preferences/IdeaIC2018.3

if [ ! -d ${ideaDir} ]
then
  ideaDir=~/Library/Preferences/IdeaIC2019.1

  if [ ! -d ${ideaDir} ]
  then
    echo "***" Intellij IDEA is not installed "***"
    exit
  fi
fi

typesDir=${ideaDir}/filetypes

if [ ! -d ${typesDir} ]
then
  mkdir ${typesDir}
fi
cp ${binDir}/Ecstasy.xml ${typesDir}

echo "***" restart the IntelliJ "***"