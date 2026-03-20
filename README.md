# Sistema para troca de mensagem instantânea

## Introdução

Este projeto consiste na implementação de um sistema distribuído baseado na comunicação entre clientes e servidores por meio de troca de mensagens. A arquitetura utiliza um componente intermediário (broker) responsável por encaminhar as requisições entre os clientes e os servidores, desacoplando a comunicação entre eles.

O sistema permite operações básicas como autenticação de usuário, listagem de canais e criação de novos canais, seguindo o padrão de comunicação Request-Reply.

## Escolhas de Implementação

### Linguagens

O sistema foi desenvolvido utilizando Python e Java. Essa escolha permite demonstrar a interoperabilidade entre diferentes linguagens dentro de um sistema distribuído, garantindo que clientes e servidores implementados em tecnologias distintas consigam se comunicar corretamente.

### Serialização

Foi utilizado Protocol Buffers (Protobuf) como formato de serialização das mensagens. Essa escolha foi feita devido à sua eficiência e padronização, além de permitir a integração entre diferentes linguagens.

### Persistência

A persistência de dados foi implementada utilizando SQLite no servidor Python. Essa escolha se deve à sua simplicidade de uso e ao fato de ser suficiente para armazenar os dados necessários, como os canais criados no sistema.
