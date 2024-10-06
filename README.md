# Tunnellen

![Build Status](https://github.com/fredrik-rambris/tunnellen/actions/workflows/java-ci.yml/badge.svg)

Tunnellen is a tool to manage port forwards to Kubernetes resources. It allows you to easily set up and manage port forwarding configurations for different environments and services.

## Features

- Manage port forwards for multiple Kubernetes contexts
- Group port forwards by environment (e.g., dev, test, prod)
- Automatically start port forwards on startup
- Simple Web UI

## Usage

1. Clone the repository:
    ```sh
    git clone https://github.com/fredrik-rambris/tunnellen.git
    cd tunnellen
    ```

2. Build the project:
    ```sh
    mvn clean package
    ```

3. Run the tool:
    ```sh
    java -jar target/tunnellen-<version>-with-dependencies.jar
    ```
