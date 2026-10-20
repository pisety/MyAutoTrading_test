#!/bin/bash
pkill -f my-auto-trading.py || true
sleep 1
source ~/venv/bin/activate
pip install -r ~/requirements.txt --quiet
nohup python ~/my-auto-trading.py > /dev/null 2>&1 & disown
