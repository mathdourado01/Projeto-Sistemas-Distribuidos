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

### Sincronização de relógio físico e eleição de coordenador

Na quarta parte do projeto, foi implementado um mecanismo de sincronização de relógio físico entre os servidores, baseado em um modelo de coordenador.

Cada servidor possui um relógio físico local que pode apresentar diferenças em relação aos demais. Para reduzir essa inconsistência, foi adotada a seguinte abordagem:

- Um servidor é eleito como **coordenador**, responsável por fornecer seu tempo como referência;
- Os demais servidores enviam requisições (`TIME_REQ`) ao coordenador para obter seu horário;
- Ao receber a resposta (`TIME_REP`), o servidor calcula um **offset** e ajusta seu relógio físico.

A escolha do coordenador é baseada no **rank dos servidores**, obtido através do serviço de referência. O servidor com menor rank é definido como coordenador inicial.

#### Detecção de falhas e eleição

Caso o coordenador não responda:

- O servidor detecta a falha por timeout;
- Inicia um processo de eleição enviando mensagens (`ELECTION_REQ`) aos demais servidores;
- Apenas servidores ativos respondem (`ELECTION_REP`);
- O novo coordenador é escolhido com base no menor rank entre os ativos;
- O resultado é divulgado via Pub/Sub no tópico `servers` com a mensagem `COORDINATOR_ANNOUNCE`.

#### Comunicação entre servidores

Foi implementado um canal direto entre servidores utilizando ZeroMQ (REQ/REP), separado da comunicação com clientes. Esse canal é utilizado para:

- sincronização de tempo;
- troca de mensagens de eleição.

#### Integração com o sistema

A sincronização ocorre de forma transparente:

- o relógio lógico continua sendo utilizado para ordenação de eventos;
- o relógio físico passa a ser ajustado dinamicamente;
- o sistema mantém funcionamento mesmo com falha do coordenador.

Essa abordagem garante maior consistência temporal e tolerância a falhas.

#### Desenvolvedores

- João Pedro Sabino Garcia - RA: 22.224.032-7
- Matheus Dourado Valle - RA: 22.224.023-6
