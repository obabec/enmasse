#!/bin/bash
sudo apt install docker.io
sudo systemctl unmask docker

sudo systemctl start docker
sudo systemctl stop docker

mkdir /mnt/docker
ln -s /mnt/docker /var/lib/docker

sudo systemctl start docker