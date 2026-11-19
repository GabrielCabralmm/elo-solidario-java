# EloSolidário API — Java Advanced

Global Solution 2026/2 · CitizenTech · FIAP 2TDS

| Integrante | RM | Turma |
|---|---|---|
| Gabriel Cabral Mendes Mariano | 563230 | 2TDSPO |
| João Victor Santana dos Santos | 566063 | 2TDSPX |

- **Deploy (Azure App Service):** https://elo-solidario.azurewebsites.net
- **Swagger:** https://elo-solidario.azurewebsites.net/swagger-ui.html
- **Deploy alternativo (Render):** https://elo-solidario-java.onrender.com ([Swagger](https://elo-solidario-java.onrender.com/swagger-ui.html))
- **Vídeo pitch (Java):** <link do YouTube>
- **Vídeo DevOps:** <link do YouTube>
- **Azure DevOps:** https://dev.azure.com/EloSolidario

## O problema e a solução

Muitas famílias vivem em insegurança alimentar enquanto alimentos ainda bons para consumo são descartados por moradores, mercados e padarias, porque não existe uma ligação simples entre quem tem e quem precisa.

O **EloSolidário** é uma rede comunitária de doação de **alimentos**. Esta API REST é o backend consumido pelo app Mobile:

- o **doador** publica uma doação com alimentos do catálogo (quantidade, peso e validade) e confirma a retirada;
- o **beneficiário** encontra doações por bairro e categoria, reserva e acompanha suas reservas;
- o **assistente com IA** transforma uma frase como *"tenho 3 pacotes de arroz e 1 litro de óleo"* em itens do catálogo prontos para confirmar;
- cada reserva gera **eventos assíncronos** que viram notificações para o doador e para o beneficiário.

ODS atendidos: **2** (Fome Zero), **10** (Redução das Desigualdades) e **12** (Consumo e Produção Responsáveis).

## Tecnologias

Java 21 · Spring Boot 3.5 · Spring Data JPA · Bean Validation · Spring Security + JWT (jjwt) · Spring AI 1.1 (cliente OpenAI compatível) · Spring AMQP (RabbitMQ) · springdoc-openapi (Swagger) · Oracle Database ou Azure SQL (SQL Server) · JUnit 5 + Mockito · Docker · Azure DevOps · Azure CLI

## Arquitetura

```mermaid
flowchart LR
    APP[App Mobile<br/>React Native] -- REST + JWT --> API
    subgraph API[EloSolidário API - Spring Boot]
        C[Controllers] --> S[Services]
        S --> R[Repositories<br/>Spring Data JPA]
        S -- evento após o commit --> P[Publisher]
        L[Consumer] --> N[NotificacaoService]
        S --> AI[AssistenteDoacaoService<br/>Spring AI]
    end
    R --> DB[(Oracle<br/>tabelas TB_ELO_*<br/>gatilhos e pacotes)]
    P -- exchange elo.reservas --> MQ[[RabbitMQ<br/>fila elo.notificacoes]]
    MQ --> L
    N --> DB
    AI -- chat completions --> LLM[LLM<br/>Gemini 3.5 Flash-Lite]
```

Organização do código (`br.com.fiap.elosolidario`):

| Pacote | Responsabilidade |
|---|---|
| `controller` | Endpoints REST e documentação Swagger |
| `service` | Regras de negócio |
| `repository` | Acesso a dados com Spring Data JPA |
| `model` | Entidades JPA e enums |
| `dto` | Contratos de entrada e saída, com Bean Validation |
| `security` | Geração e validação do JWT, filtro e respostas 401/403 |
| `messaging` | Evento, publicação e consumo no RabbitMQ |
| `exception` | Exceções de negócio e `@RestControllerAdvice` |
| `config` | Segurança, CORS, RabbitMQ, Swagger e paginação |

## Modelo de domínio

| Entidade | Tabela | Relacionamentos |
|---|---|---|
| `Usuario` | `TB_ELO_USUARIO` | 1:N doações, reservas e notificações |
| `Categoria` | `TB_ELO_CATEGORIA` | 1:N alimentos |
| `Alimento` | `TB_ELO_ALIMENTO` | N:1 categoria |
| `Doacao` | `TB_ELO_DOACAO` | N:1 doador, 1:N itens (cascade) e reservas |
| `ItemDoacao` | `TB_ELO_ITEM_DOACAO` | N:1 doação e alimento |
| `Reserva` | `TB_ELO_RESERVA` | N:1 doação e beneficiário |
| `Notificacao` | `TB_ELO_NOTIFICACAO` | N:1 usuário |

A mesma API roda em dois bancos, e a URL em `DB_URL` define qual é usado. No **Oracle**, as tabelas, gatilhos e pacotes PL/SQL são criados pelos scripts da disciplina de Banco de Dados. No **Azure SQL**, que é o banco da nuvem, eles são criados pelo `scripts/script-bd.sql` em T-SQL, com gatilhos equivalentes. A regra "uma doação só pode ter uma reserva ativa" é validada na API, que devolve uma mensagem amigável, e garantida no Oracle pelo gatilho `TRG_ELO_RESERVA_STATUS`, que atualiza o status da doação. Depois de gravar a reserva, a API recarrega a doação para refletir o status definido pelo banco.

## Segurança e perfis

O login devolve um JWT (HS256, 2 horas) que deve ser enviado no cabeçalho `Authorization: Bearer <token>`. As permissões são aplicadas com `@PreAuthorize` em cada endpoint:

| Perfil | Pode |
|---|---|
| `DOADOR` | Publicar, editar e excluir as próprias doações, usar o assistente, confirmar retiradas |
| `BENEFICIARIO` | Reservar doações, cancelar as próprias reservas, ver o histórico |
| `ADMIN` | Gerenciar qualquer doação e listar usuários |

O cadastro público só cria `DOADOR` ou `BENEFICIARIO`. As senhas são gravadas com BCrypt. Sem token ou com token inválido, a API responde **401**. Com um perfil sem permissão, responde **403**.

## Requisito 5 — Arquitetura distribuída: opção A (RabbitMQ)

| Item | Valor |
|---|---|
| Exchange | `elo.reservas` (topic) |
| Routing keys | `reserva.reserva_criada`, `reserva.reserva_concluida`, `reserva.reserva_cancelada` |
| Fila | `elo.notificacoes` (binding `reserva.#`), com dead letter `elo.notificacoes.erro` |
| Publicação | `ReservaEventoPublisher`, com `@TransactionalEventListener`: só publica depois que a reserva foi gravada |
| Consumo | `NotificacaoConsumer` (`@RabbitListener`), que grava a notificação para o destinatário |

Fluxo: o beneficiário reserva → o evento `RESERVA_CRIADA` vai para a fila → o consumidor avisa o doador. Ao confirmar a retirada, o beneficiário recebe `RESERVA_CONCLUIDA`. Se a reserva for cancelada, o doador recebe `RESERVA_CANCELADA`. Se o RabbitMQ estiver fora do ar, a reserva continua funcionando e a falha fica registrada no log. Uma mensagem que falhar 3 vezes no consumidor vai para a fila de erro.

## Spring AI — Assistente de doação

`POST /assistente/sugestao` (perfil DOADOR)

1. O serviço monta um prompt de sistema com as regras e o **catálogo de alimentos ativos** do banco (id, nome, categoria e conservação).
2. O modelo responde em JSON estruturado (`.entity(RespostaIA.class)` do Spring AI).
3. A API **valida a resposta**: descarta ids que não existem no catálogo e itens repetidos, corrige unidade e quantidade inválidas e acrescenta uma orientação para itens refrigerados ou congelados.
4. O app mostra a sugestão para o doador conferir, informar a validade e publicar com `POST /doacoes`.

Exemplo de entrada:

```json
{ "texto": "Tenho 3 pacotes de arroz, 2 de feijão, 1 litro de óleo e um sabonete" }
```

Exemplo de resposta:

```json
{
  "tituloSugerido": "Cesta com arroz, feijão e óleo",
  "itens": [
    { "idAlimento": 1, "alimento": "Arroz branco", "categoria": "Grãos e Cereais", "conservacao": "AMBIENTE", "quantidade": 3.0, "unidade": "PCT", "pesoKgEstimado": 3.0 },
    { "idAlimento": 2, "alimento": "Feijão carioca", "categoria": "Grãos e Cereais", "conservacao": "AMBIENTE", "quantidade": 2.0, "unidade": "PCT", "pesoKgEstimado": 2.0 },
    { "idAlimento": 13, "alimento": "Óleo de soja", "categoria": "Óleos e Temperos", "conservacao": "AMBIENTE", "quantidade": 1.0, "unidade": "L", "pesoKgEstimado": 0.9 }
  ],
  "naoReconhecidos": ["sabonete"],
  "orientacoes": ["Mantenha os pacotes fechados e em local seco."],
  "aviso": "Sugestão gerada por IA. Confira os itens, ajuste as quantidades e informe a validade de cada alimento antes de publicar. A avaliação das condições do alimento é sempre do doador."
}
```

Limitações e uso responsável:

- a IA **não publica nada sozinha** e **não certifica a segurança** de nenhum alimento;
- o peso é uma estimativa, e a validade é sempre informada pelo doador;
- se o provedor de IA estiver indisponível, a API responde **503** e o cadastro manual continua funcionando.

O provedor é configurável por variáveis de ambiente, porque o Spring AI usa o padrão de API da OpenAI:

| Provedor | `AI_BASE_URL` | `AI_COMPLETIONS_PATH` | `AI_MODEL` |
|---|---|---|---|
| Google Gemini (padrão) | `https://generativelanguage.googleapis.com/v1beta/openai` | `/chat/completions` | `gemini-3.5-flash-lite` |
| OpenAI | `https://api.openai.com` | `/v1/chat/completions` | `gpt-4o-mini` |
| Groq | `https://api.groq.com/openai` | `/v1/chat/completions` | `llama-3.3-70b-versatile` |

## Endpoints principais

| Método | Rota | Perfil | Descrição |
|---|---|---|---|
| POST | `/auth/registrar` | público | Cadastro de doador ou beneficiário |
| POST | `/auth/login` | público | Login, devolve o JWT |
| GET | `/auth/me` | logado | Dados do usuário logado |
| GET | `/categorias` | logado | Categorias de alimento |
| GET | `/categorias/{id}` | logado | Busca uma categoria |
| POST | `/categorias` | ADMIN | Cria uma categoria |
| PUT | `/categorias/{id}` | ADMIN | Atualiza uma categoria |
| DELETE | `/categorias/{id}` | ADMIN | Exclui uma categoria sem alimentos |
| GET | `/alimentos?idCategoria=&nome=&page=&size=` | logado | Catálogo paginado |
| GET | `/doacoes?status=&bairro=&idCategoria=&page=&size=&sort=` | logado | Vitrine paginada (padrão: disponíveis) |
| GET | `/doacoes/{id}` | logado | Detalhe com os alimentos |
| GET | `/doacoes/minhas` | DOADOR | Doações do doador |
| POST | `/doacoes` | DOADOR, ADMIN | Publica uma doação |
| PUT | `/doacoes/{id}` | DOADOR, ADMIN | Edita uma doação disponível |
| DELETE | `/doacoes/{id}` | DOADOR, ADMIN | Exclui uma doação sem reservas |
| POST | `/doacoes/{id}/reservas` | BENEFICIARIO | Reserva uma doação |
| GET | `/doacoes/{id}/reservas` | DOADOR, ADMIN | Reservas de uma doação |
| GET | `/reservas/minhas` | BENEFICIARIO | Reservas do beneficiário |
| PATCH | `/reservas/{id}/concluir` | DOADOR, ADMIN | Confirma a retirada |
| PATCH | `/reservas/{id}/cancelar` | envolvidos, ADMIN | Cancela a reserva |
| POST | `/assistente/sugestao` | DOADOR, ADMIN | Sugestão de itens com IA |
| GET | `/notificacoes` | logado | Notificações do usuário |
| PATCH | `/notificacoes/{id}/lida` | dono | Marca como lida |
| GET | `/usuarios` | ADMIN | Lista de usuários |
| GET | `/status` | público | Verificação da API |

Exemplo de publicação de doação:

```json
POST /doacoes
{
  "titulo": "Cesta básica",
  "bairro": "Vila Mariana",
  "localRetirada": "Portaria do prédio, Rua Domingos de Morais, 900",
  "observacao": "Produtos fechados",
  "itens": [
    { "idAlimento": 1, "quantidade": 5, "unidade": "PCT", "pesoKg": 5, "dtValidade": "2027-03-10" },
    { "idAlimento": 13, "quantidade": 1, "unidade": "L", "pesoKg": 0.9, "dtValidade": "2027-05-15" }
  ]
}
```

## Validação e erros

Os DTOs usam Bean Validation (`@NotBlank`, `@Email`, `@Positive`, `@FutureOrPresent`, `@Valid` na lista de itens), e o `GlobalExceptionHandler` (`@RestControllerAdvice`) devolve sempre o mesmo formato:

```json
{
  "timestamp": "2026-11-16T10:42:16",
  "status": 400,
  "erro": "Bad Request",
  "mensagem": "Dados inválidos",
  "caminho": "/doacoes",
  "campos": [ { "campo": "itens[0].dtValidade", "mensagem": "Não é permitido doar alimento com a validade vencida" } ]
}
```

| Status | Quando |
|---|---|
| 400 | Dados inválidos, JSON mal formado ou parâmetro com tipo errado |
| 401 | Sem token, token inválido ou login incorreto |
| 403 | Perfil sem permissão ou recurso de outro usuário |
| 404 | Doação, reserva, alimento ou rota inexistente |
| 409 | Regra de negócio: doação indisponível, reserva duplicada, alimento vencido ou inativo, e-mail já cadastrado. Também os erros disparados pelos gatilhos (`ORA-20xxx` no Oracle e `THROW 50xxx` no SQL Server) |
| 503 | Assistente de IA indisponível |

## Usuários de teste

Todos com a senha **`Elo@2026`** (criados pelo `04_inserts.sql` do Banco, no Oracle, ou pelo `scripts/script-bd.sql`, no Azure SQL):

| E-mail | Perfil |
|---|---|
| `ana.souza@email.com` | DOADOR |
| `contato@bompreco.com` | DOADOR |
| `maria.oliveira@email.com` | BENEFICIARIO |
| `reservas@laresperanca.org` | BENEFICIARIO |
| `admin@elosolidario.com` | ADMIN |

## Como rodar

Pré-requisitos: Java 21, Maven 3.9, um RabbitMQ e um banco com as tabelas criadas: o Oracle com os scripts do Banco (`01_ddl.sql` a `04_inserts.sql`) ou o SQL Server/Azure SQL com o `scripts/script-bd.sql`.

1. Suba o RabbitMQ local (painel em `http://localhost:15672`, usuário e senha `guest`):

   ```bash
   docker compose up -d
   ```

2. Configure as variáveis de ambiente, usando o `.env.example` como modelo:

   | Variável | Descrição |
   |---|---|
   | `DB_URL` | URL JDBC do Oracle (padrão: Oracle FIAP) ou do SQL Server, ex.: `jdbc:sqlserver://servidor.database.windows.net:1433;databaseName=db-elosolidario;encrypt=true` |
   | `DB_USERNAME` / `DB_PASSWORD` | Credenciais do Oracle |
   | `JWT_SECRET` | Segredo do token, com pelo menos 32 caracteres |
   | `RABBITMQ_URL` | URL AMQP (padrão: `amqp://guest:guest@localhost:5672`) |
   | `AI_API_KEY`, `AI_BASE_URL`, `AI_COMPLETIONS_PATH`, `AI_MODEL` | Provedor de IA (ver tabela acima) |

3. Execute:

   ```bash
   mvn spring-boot:run
   ```

4. Acesse `http://localhost:8080/swagger-ui.html`, faça o login em `/auth/login` e clique em **Authorize** com o token.

Testes unitários:

```bash
mvn test
```

### Deploy no Render

O projeto tem um Dockerfile com build multi-stage em `dockerfiles/Dockerfile`. No Render: **New Web Service → Docker**, apontando para este repositório, com **Dockerfile Path** `./dockerfiles/Dockerfile` e as variáveis de ambiente acima. Para o RabbitMQ em nuvem, pode ser usada uma instância gratuita do CloudAMQP: basta copiar a URL `amqps://...` para `RABBITMQ_URL`.

## DevOps e nuvem (Azure)

A API Java roda na **Azure**: o deploy é feito por **CI/CD no Azure DevOps** em um **App Service**, e os dados ficam no **Azure SQL Database**. Toda a infraestrutura é criada por **script Azure CLI**.

![Arquitetura macro](docs/arquitetura.png)

### Estrutura do repositório

| Caminho | Conteúdo |
|---|---|
| `azure-pipeline.yml` | Pipeline YAML com os estágios **Build (CI)** e **Release (CD)** |
| `scripts/infra-azure.sh` | Azure CLI: Resource Group, Azure SQL (servidor, firewall e banco), App Service Linux Java 21 e variáveis de ambiente. Também executa o `script-bd.sql` |
| `scripts/script-bd.sql` | DDL do banco em T-SQL: 8 tabelas, gatilhos de auditoria e de reservas, e carga inicial |
| `scripts/devops-configuracao.sh` | Azure DevOps CLI: projeto privado, import do repositório, políticas da main, grupo de variáveis secretas, pipeline e acesso do professor |
| `scripts/remover-recursos.sh` | Remove o Resource Group depois da avaliação |
| `dockerfiles/Dockerfile` | Imagem Docker da API (usada no deploy alternativo no Render) |

### Recursos na Azure

| Recurso | Nome | Configuração |
|---|---|---|
| Resource Group | `rg-elosolidario` | Região `chilecentral` (Chile Central, definida na variável `LOCATION`) |
| Azure SQL Server | `sql-elosolidario` | Firewall: serviços da Azure + IP de quem roda o script |
| Azure SQL Database | `db-elosolidario` | Camada Basic |
| App Service Plan | `plan-elosolidario` | Linux, B1 |
| Web App | `elo-solidario` | Java 21 (Java SE), Always On, com as variáveis de ambiente da API |

### Como provisionar

No **Azure Cloud Shell (Bash)**, que já vem com o Azure CLI (o script baixa o `go-sqlcmd` para executar o `script-bd.sql`):

```bash
git clone https://github.com/GabrielCabralmm/elo-solidario-java.git && cd elo-solidario-java
bash scripts/infra-azure.sh
```

Antes de rodar, preencha no topo do script as variáveis `RABBITMQ_URL` (URL `amqps://` do CloudAMQP) e `AI_API_KEY` (chave do Gemini), por exemplo com `nano scripts/infra-azure.sh`. Elas ficam com `PREENCHER` no repositório porque ele é público. Se a assinatura não permitir a região padrão (`chilecentral`), troque `LOCATION` para `eastus2`.

### Azure DevOps

```bash
export ORGANIZACAO=https://dev.azure.com/<sua-organizacao>
export REVISOR=rm563230@fiap.com.br
export PROFESSOR=<e-mail do professor>
az login && bash scripts/devops-configuracao.sh
```

O script cria ou configura:

- o **projeto privado** com Git e o repositório importado do GitHub;
- as **políticas da branch main**:
  - pelo menos 1 aprovação, com o autor podendo aprovar o próprio PR (simulação);
  - **work item vinculado** obrigatório;
  - **revisor obrigatório** (RM563230);
- o **grupo de variáveis `elo-solidario-segredos`**, com os segredos marcados como *secret*;
- a **pipeline** apontando para `azure-pipeline.yml`;
- o professor com licença **Basic** e no grupo **Project Administrators**.

Falta um passo manual: criar a **service connection** do tipo *Azure Resource Manager* chamada `conexao-azure-elosolidario`, em **Project Settings → Service connections**, com a assinatura e o Resource Group `rg-elosolidario`.

### Pipeline (`azure-pipeline.yml`)

| Estágio | Quando roda | Etapas |
|---|---|---|
| **Build (CI)** | A cada commit na `main`, ou seja, só depois do merge do PR (`pr: none`) | Cache do Maven → `mvn clean package` com Java 21 (compila e roda os **testes JUnit**) → publica os **resultados dos testes** → publica o **artefato** (`.jar` + `scripts/`) |
| **Release (CD)** | Automaticamente, quando o Build gera um novo artefato | Ambiente `producao` → baixa o artefato → `AzureWebApp@1` faz o deploy do `.jar` no App Service e aplica as **variáveis de ambiente** → teste de fumaça em `GET /status` |

Variáveis usadas na pipeline:

| Variável | Onde fica | Uso |
|---|---|---|
| `azureSubscription`, `webAppName`, `artefato` | `azure-pipeline.yml` | Service connection, nome do App Service e nome do artefato |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Grupo `elo-solidario-segredos` (secret) | Conexão com o Azure SQL |
| `JWT_SECRET` | Grupo `elo-solidario-segredos` (secret) | Assinatura dos tokens |
| `RABBITMQ_URL`, `AI_API_KEY` | Grupo `elo-solidario-segredos` (secret) | CloudAMQP e Gemini |

### Fluxo de trabalho

1. Criar a tarefa no **Azure Boards**, por exemplo *"Exibir a versão da API no /status"*.
2. Criar a branch a partir da tarefa, ou com `git checkout -b feature/<id>-descricao`.
3. Alterar o **código-fonte**, fazer o commit com `AB#<id>` na mensagem e o push.
4. Abrir o **Pull Request** para a `main`, vinculado à tarefa, e aprová-lo como revisor (RM).
5. Fazer o **merge**: o Build roda, publica os testes e o artefato, e a Release faz o deploy sozinha.
6. Conferir a alteração na API publicada e fechar a tarefa, com os links de branch, commits e PR.

### CRUD em duas tabelas (JSON)

Base: `https://elo-solidario.azurewebsites.net`. Primeiro faça o login e use o token em `Authorization: Bearer <token>`:

```json
POST /auth/login
{ "email": "admin@elosolidario.com", "senha": "Elo@2026" }
```

**Tabela `TB_ELO_CATEGORIA`** (perfil ADMIN):

```json
POST /categorias
{ "nome": "Bebidas", "perecivel": false }

GET /categorias/{id}

PUT /categorias/{id}
{ "nome": "Bebidas e Sucos", "perecivel": true }

DELETE /categorias/{id}
```

**Tabelas `TB_ELO_DOACAO` e `TB_ELO_ITEM_DOACAO`** (perfil DOADOR, login `ana.souza@email.com`):

```json
POST /doacoes
{
  "titulo": "Cesta para a demonstração",
  "bairro": "Vila Mariana",
  "localRetirada": "Portaria do prédio, Rua Domingos de Morais, 900",
  "observacao": "Produtos fechados",
  "itens": [
    { "idAlimento": 1, "quantidade": 2, "unidade": "PCT", "pesoKg": 2, "dtValidade": "2027-03-10" },
    { "idAlimento": 13, "quantidade": 1, "unidade": "L", "pesoKg": 0.9, "dtValidade": "2027-05-15" }
  ]
}

GET /doacoes/{id}

PUT /doacoes/{id}
{
  "titulo": "Cesta para a demonstração (editada)",
  "bairro": "Vila Mariana",
  "localRetirada": "Portaria do prédio, Rua Domingos de Morais, 900",
  "observacao": "Retirar até as 18h",
  "itens": [
    { "idAlimento": 1, "quantidade": 3, "unidade": "PCT", "pesoKg": 3, "dtValidade": "2027-03-10" },
    { "idAlimento": 2, "quantidade": 1, "unidade": "PCT", "pesoKg": 1, "dtValidade": "2027-01-20" }
  ]
}

DELETE /doacoes/{id}
```

Para conferir cada operação **direto no banco** (Query editor do Azure SQL no portal, Azure Data Studio ou `sqlcmd`):

```sql
SELECT * FROM TB_ELO_CATEGORIA ORDER BY ID_CATEGORIA DESC;

SELECT * FROM TB_ELO_DOACAO ORDER BY ID_DOACAO DESC;
SELECT * FROM TB_ELO_ITEM_DOACAO ORDER BY ID_ITEM DESC;

SELECT TOP 10 * FROM TB_ELO_AUDITORIA_DOACAO ORDER BY ID_AUDITORIA DESC;
```

O gatilho `TRG_ELO_AUDITORIA_DOACAO` grava cada INSERT, UPDATE e DELETE de doação em `TB_ELO_AUDITORIA_DOACAO`, o que deixa as operações do CRUD rastreáveis no banco.

## Declaração de uso de IA

Ferramenta utilizada: Claude (Anthropic), como apoio na revisão do código, na explicação de erros e na revisão do texto deste README. O código foi revisado, executado e testado pelos integrantes.
