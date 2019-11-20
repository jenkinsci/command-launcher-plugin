Command Agent Launcher Plugin for Jenkins
=========================================

[![Jenkins Plugin](https://img.shields.io/jenkins/plugin/v/command-launcher.svg)](https://plugins.jenkins.io/command-launcher)
[![Jenkins Plugin Installs](https://img.shields.io/jenkins/plugin/i/command-launcher.svg?color=blue)](https://plugins.jenkins.io/command-launcher)

Allows agents to be launched using a specified command.

## Usage

The plugin adds a new agent _Launch method_ which starts an agent by having Jenkins execute a command from the master.
Use this when the master is capable of remotely executing a process on another machine, e.g. via SSH or RSH.

![Configuration](/docs/images/command-launcher.png)

## Release notes

See the [changelog](./CHANGELOG.md)
