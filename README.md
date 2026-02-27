# Signaling Gateway

Signaling Gateway is an open-source core signaling component designed to bridge and process telecom signaling traffic within modern carrier-grade architectures.

It provides a modular and extensible foundation for handling signaling flows and protocol interactions, and is intended to be embedded as part of larger telecom platforms and network functions, enabling bi-directional, multi-channel, multi-layered concurrent flows of asynchronous messages from different base protocol implementations such as: SS7 MAP/CAP; Telco-IP based protocols such as: IMS SIP, Diameter, SMPP OTA, etc., acting as a gateway for tailor-made applications with a complete set of API interfaces and simple integration with data storage engines.

Two main components used for different extensions of the system: 
- Application API: main object to be instantiated creating a logic within the system, created inheriting the Application class and placing it as a new class in com.paic.esg.impl.app package.
- Channel API: it does have to inherit from the homonym class implementing workers mechanism for a finite state machine processing, as a logic element extracting messages from an underlying layer. 


This repository contains the open-source core of the Signaling Gateway, published and maintained by **PAiCore Technology** as part of its commitment to open innovation.

For more information about PAiCore Technology, visit:  
https://paicore.tech

## Build Prerequisites

- Java 11 (OpenJDK recommended)
- Maven 3.x
- Linux-based environment

## Build from Source

```bash
git clone https://github.com/paicoretech/Signaling-Gateway.git
cd Signaling-Gateway
mvn clean install
```

## License

This project is licensed under the **GNU Affero General Public License v3.0 (AGPL-3.0)**.  
See the [LICENSE](LICENSE) file for details.
