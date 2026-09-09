# Arquitetura do Porteirinho

## Princípio central

O aparelho registra primeiro e sincroniza depois. Room é a fonte de verdade operacional durante o turno. O servidor só é marcado como confirmado quando a Edge Function responde com sucesso.

## Fluxo de um evento

1. A regra de domínio valida usuário, aparelho, janela e QR.
2. A alteração operacional e o `outbox_event` são gravados na mesma transação Room.
3. WorkManager aguarda conectividade e envia lotes de até 50 eventos.
4. A Edge Function autentica o dispositivo e insere `event_id` com chave única.
5. O evento local muda para `SYNCED` somente após a confirmação.
6. Erros transitórios voltam com backoff; erros 4xx permanentes ficam visíveis para a administração.

## Camadas

- `ui`: telas Compose e scanner CameraX/ML Kit.
- `domain`: janela de horário, identidade do aparelho, formato de QR e resultados de regras.
- `data/local`: modelo Room, DAOs e fila de saída.
- `data/PatrolRepository`: transações e regras operacionais.
- `sync`: WorkManager e cliente de ingestão.
- `supabase`: migração PostgreSQL, RLS e Edge Function.

## Escala e retenção

As tabelas de maior crescimento possuem índices por organização, status e data. No aparelho, a outbox é separada das visões operacionais. Eventos confirmados não devem ser apagados por tempo até existir uma política formal de retenção e uma exportação auditável. No servidor, particionamento mensal de `ingested_events`, `checkpoint_visits` e `audit_logs` pode ser introduzido quando o volume justificar, sem mudar o protocolo móvel.

## Próximos módulos

- CRUD administrativo completo com arquivamento/restauração.
- provisionamento seguro de dispositivos e rotação do token.
- geração, impressão, substituição e revogação de QR Codes.
- materialização assíncrona dos eventos ingeridos nas tabelas operacionais.
- anexos de ocorrências no Storage.
- relatórios e filtros de histórico.
- testes instrumentados de Room, CameraX e recuperação após reinicialização.
