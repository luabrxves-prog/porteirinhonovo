# Porteirinho

Aplicativo Android nativo para controle, execução e auditoria de rondas presenciais por QR Code. A arquitetura é **offline-first**: o porteiro continua trabalhando sem internet e nenhum evento sai da fila local antes da confirmação do servidor.

## O que já está nesta primeira versão

- projeto Kotlin + Jetpack Compose preparado para Android;
- separação visual entre Portaria e Administração;
- perfis com PIN, limite de tentativas e bloqueio temporário;
- início e encerramento de turno;
- programação compatível com rondas que atravessam a meia-noite;
- atribuição opcional de responsáveis;
- recuperação de ronda ativa após o aplicativo ser fechado;
- execução com progresso e pontos pendentes/concluídos;
- leitura exclusivamente pela câmera usando CameraX e ML Kit;
- validação de QR desconhecido, revogado, inativo ou fora da ronda;
- detecção de alteração do relógio e intervalo impossível entre pontos;
- Room com eventos, alertas, ocorrências, auditoria e outbox;
- sincronização automática com WorkManager;
- ingestão idempotente em Supabase Edge Functions;
- esquema PostgreSQL multiempresa, RLS, índices e proteção contra exclusão de evidências;
- painel administrativo inicial com indicadores e central de alertas.

## Executar no Android Studio

1. Instale Android Studio com Android SDK 36 e JDK 17.
2. Abra a pasta raiz do projeto.
3. Crie `local.properties` com o caminho do SDK e, quando o backend existir, as credenciais públicas:

```properties
sdk.dir=C:\\Users\\SEU_USUARIO\\AppData\\Local\\Android\\Sdk
SUPABASE_URL=https://SEU_PROJETO.supabase.co
SUPABASE_PUBLISHABLE_KEY=SUA_CHAVE_PUBLICAVEL
```

4. Sincronize o Gradle e execute em um aparelho Android 8 ou superior.

Sem credenciais Supabase, o aplicativo funciona em modo local e mantém os eventos como pendentes. O PIN dos dois perfis de demonstração é `1234`.

## Validar pela linha de comando

O projeto usa Gradle 8.13, AGP 8.13.2 e JDK 17. Em uma máquina com Gradle instalado:

```bash
gradle testDebugUnitTest lintDebug
```

O CI executa a mesma validação a cada push e pull request.

## Configurar o Supabase

1. Crie um projeto Supabase.
2. Aplique `supabase/migrations/202609090001_initial_schema.sql`.
3. Publique a função `ingest-events`.
4. Cadastre o dispositivo com `public_id`, nome e `api_token_hash` SHA-256.
5. Grave o token original no `SecureStore` do aparelho durante o fluxo de provisionamento; nunca grave o token puro no banco.

```bash
supabase db push
supabase functions deploy ingest-events
```

A chave `service_role` é usada apenas dentro da Edge Function. O APK recebe somente a chave publicável.

## QR Codes de demonstração

Para testar, gere imagens QR com estes conteúdos e leia-as pela câmera:

```text
porteirinho:v1:qr-demo-1:DEMO-CHECKPOINT-GATE-2026
porteirinho:v1:qr-demo-2:DEMO-CHECKPOINT-GARAGE-2026
porteirinho:v1:qr-demo-3:DEMO-CHECKPOINT-HALL-2026
```

Digitação manual não existe no fluxo do porteiro.

## Estrutura

```text
app/src/main/java/br/com/porteirinho/
├── data/          regras transacionais e banco Room
├── domain/        janela de ronda, QR e identidade do aparelho
├── security/      PIN e Android Keystore
├── sync/          outbox e WorkManager
└── ui/            Compose e scanner

supabase/
├── migrations/    PostgreSQL, índices, RLS e auditoria
└── functions/     endpoint idempotente de ingestão
```

Leia também [Arquitetura](docs/ARCHITECTURE.md) e [Segurança](docs/SECURITY.md).

## Estado do produto

Esta é a fundação funcional do produto, criada a partir de um repositório vazio. Ainda precisam ser concluídos antes de um APK de produção: CRUD administrativo completo, provisionamento de aparelhos, emissão/impressão/rotação de QR, materialização dos eventos, fotos de ocorrência, relatórios avançados, testes instrumentados e assinatura de release.
