#!/bin/bash
pkill -f python
sleep 2
cd ~
nohup python3 my-auto-trading.py > nohup.out 2>&1 &
