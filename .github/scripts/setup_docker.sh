#!/bin/bash
set -e

sudo apt-get install \
    apt-transport-https \
    ca-certificates \
    curl \
    gnupg-agent \
    software-properties-common

curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo apt-key add -

sudo add-apt-repository \
   "deb [arch=amd64] https://download.docker.com/linux/ubuntu \
   $(lsb_release -cs) \
   stable"

sudo apt-get update

sudo apt-get install docker-ce docker-ce-cli containerd.io

sudo systemctl stop docker

sudo mkdir /mnt/docker
#sudo echo 'DOCKER_OPTS="-dns 8.8.8.8 -dns 8.8.4.4 -g /mnt/docker"' > /etc/default/docker

sudo sh -c "echo 'ExecStart=/usr/bin/docker daemon -g /new/path/docker -H fd://' >> /lib/systemd/system/docker.service"

sudo systemctl daemon-reload
sudo rsync -aqxP /var/lib/docker/ /mnt/docker

sudo systemctl start docker

ps aux | grep -i docker | grep -v grep