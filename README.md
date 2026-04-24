# Sistema para troca de mensagem instantânea

## Introdução

Este projeto consiste na implementação de um sistema distribuído baseado na comunicação entre clientes e servidores por meio de troca de mensagens. A arquitetura utiliza componentes intermediários responsáveis por encaminhar requisições e publicações, desacoplando a comunicação entre os processos.

O sistema permite operações como autenticação de usuário, listagem de canais, criação de canais e publicação de mensagens em canais, utilizando comunicação Request-Reply e Publish-Subscribe.

## Escolhas de Implementação

### Linguagens

O sistema foi desenvolvido utilizando Python e Java. Essa escolha permite demonstrar a interoperabilidade entre diferentes linguagens dentro de um sistema distribuído, garantindo que clientes e servidores implementados em tecnologias distintas consigam se comunicar corretamente.

### Serialização

Foi utilizado Protocol Buffers (Protobuf) como formato de serialização das mensagens. Essa escolha foi feita devido à sua eficiência e padronização, além de permitir a integração entre diferentes linguagens.

### Comunicação

Na primeira parte, foi utilizado o padrão Request-Reply com ZeroMQ para a comunicação entre clientes, broker e servidores. Nesse modelo, o cliente envia uma requisição ao servidor e recebe uma resposta correspondente.

Na segunda parte, foi adicionado o padrão Publish-Subscribe (Pub/Sub) para permitir a publicação e o recebimento de mensagens em canais. Para isso, foi implementado um proxy Pub/Sub separado do broker principal, utilizando sockets XSUB e XPUB do ZeroMQ.

O fluxo de publicação funciona da seguinte forma:

- O cliente envia uma requisição de publicação (PUBLISH_REQ) ao servidor;
- O servidor processa a requisição e publica a mensagem no canal correspondente;
- O proxy Pub/Sub encaminha a mensagem para os clientes inscritos nesse canal;
- Os clientes recebem e exibem as mensagens com canal, conteúdo, timestamp de envio e timestamp de recebimento.

Essa abordagem permite comunicação assíncrona e desacoplada entre múltiplos clientes.

### Persistência

A persistência de dados foi implementada utilizando SQLite nos servidores.

Na primeira parte, eram armazenados os logins e os canais criados. Na segunda parte, o sistema passou a armazenar também as mensagens publicadas nos canais.

Para cada mensagem publicada, são registrados:

- usuário que enviou;
- canal;
- conteúdo da mensagem;
- timestamp de envio.

Essa persistência permite manter um histórico das interações realizadas no sistema.

### Relógio lógico

Na terceira parte, foi implementado um relógio lógico nos bots e nos servidores. Cada processo mantém um contador interno que é incrementado antes do envio de cada mensagem.

Quando uma mensagem é recebida, o processo compara seu relógio lógico local com o valor recebido na mensagem e atualiza seu contador com o maior valor. Com isso, todas as mensagens passam a carregar, além do timestamp físico, o valor do relógio lógico do processo emissor.

### Serviço de referência e heartbeat

Também foi implementado um serviço de referência responsável por auxiliar na sincronização entre servidores. Esse serviço mantém a lista de servidores disponíveis, atribui um rank para cada servidor e responde às mensagens de heartbeat.

Ao iniciar, cada servidor solicita seu rank ao serviço de referência. Durante a execução, os servidores enviam heartbeats periodicamente para indicar que continuam ativos. A resposta do serviço de referência também contém o horário usado para ajustar o relógio físico lógico do servidor.

### Proxy Pub/Sub

Foi implementado um proxy dedicado para o modelo Publish-Subscribe, separado do broker utilizado para Request-Reply.

O proxy utiliza sockets XSUB e XPUB do ZeroMQ para encaminhar mensagens entre servidores (publishers) e clientes (subscribers).

Essa separação permite manter a arquitetura modular e escalável.

#### Desenvolvedores

- João Pedro Sabino Garcia - RA: 22.224.032-7
- Matheus Dourado Valle - RA: 22.224.023-6
