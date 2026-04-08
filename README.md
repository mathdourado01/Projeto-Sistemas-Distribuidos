# Sistema para troca de mensagem instantânea

## Introdução

Este projeto consiste na implementação de um sistema distribuído baseado na comunicação entre clientes e servidores por meio de troca de mensagens. A arquitetura utiliza um componente intermediário (broker) responsável por encaminhar as requisições entre os clientes e os servidores, desacoplando a comunicação entre eles.

O sistema permite operações básicas como autenticação de usuário, listagem de canais e criação de novos canais, seguindo o padrão de comunicação Request-Reply.

## Escolhas de Implementação

### Linguagens

O sistema foi desenvolvido utilizando Python e Java. Essa escolha permite demonstrar a interoperabilidade entre diferentes linguagens dentro de um sistema distribuído, garantindo que clientes e servidores implementados em tecnologias distintas consigam se comunicar corretamente.

### Serialização

Foi utilizado Protocol Buffers (Protobuf) como formato de serialização das mensagens. Essa escolha foi feita devido à sua eficiência e padronização, além de permitir a integração entre diferentes linguagens.

### Comunicação

Além do modelo Request-Reply utilizado na primeira parte, foi adicionado o padrão Publish-Subscribe (Pub/Sub) para permitir a troca de mensagens entre usuários em canais.

Para isso, foi implementado um proxy Pub/Sub utilizando ZeroMQ, responsável por intermediar a comunicação entre publishers (servidores) e subscribers (clientes).

O fluxo funciona da seguinte forma:

- O cliente envia uma requisição de publicação (PUBLISH_REQ) ao servidor
- O servidor processa a requisição e publica a mensagem no canal correspondente
- O proxy Pub/Sub encaminha a mensagem para todos os clientes inscritos nesse canal
- Os clientes recebem e exibem as mensagens com informações como canal, conteúdo e timestamps

Essa abordagem permite comunicação assíncrona e desacoplada entre múltiplos clientes.

### Persistência

### Persistência

A persistência de dados foi implementada utilizando SQLite no servidor Python.

Na primeira parte, eram armazenados apenas os logins e os canais criados. Na segunda parte, o sistema passou a armazenar também as mensagens publicadas nos canais.

Para cada mensagem, são registrados:
- usuário que enviou
- canal
- conteúdo da mensagem
- timestamp de envio

Essa persistência permite manter um histórico das interações realizadas no sistema.

### Proxy Pub/Sub

Foi implementado um proxy dedicado para o modelo Publish-Subscribe, separado do broker utilizado para Request-Reply.

O proxy utiliza sockets XSUB e XPUB do ZeroMQ para encaminhar mensagens entre servidores (publishers) e clientes (subscribers).

Essa separação permite manter a arquitetura modular e escalável.

#### Desenvolvedores
- João Pedro Sabino Garcia - RA: 22.224.032-7

- Matheus Dourado Valle - RA: 22.224.023-6
