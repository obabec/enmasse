#!/bin/bash
sudo apt install docker.io
sudo systemctl unmask docker

sudo systemctl start docker
sudo systemctl stop docker

sudo mkdir /mnt/docker
sudo ln -s /mnt/docker /var/lib/docker

sudo systemctl start docker