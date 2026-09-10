# Sincronização multi-dispositivo e operação offline

## Objetivo

O Porteirinho precisa funcionar em vários celulares de porteiros e administradores, inclusive quando o aparelho do porteiro estiver sem internet durante a ronda.

A arquitetura recomendada é **offline-first com servidor central autoritativo**:

- **Room (Android)** guarda a operação local e nunca depende da internet para aceitar uma leitura válida;
- **Outbox local** registra cada evento na mesma transação da operação;
- **WorkManager** envia os eventos quando houver conectividade;
- **Supabase/PostgreSQL** é a fonte central compartilhada entre todos os aparelhos;
- configurações administrativas e alterações feitas em outro aparelho precisam ser trazidas de volta por uma etapa de **pull/snapshot**;
- eventos usam UUID e processamento idempotente para não duplicar leituras quando houver retry.

## O que já existe

O projeto já possui Room, outbox, WorkManager e a Edge Function `ingest-events`. Isso resolve a parte de **push confiável** do aparelho para o servidor.

## O que ainda é obrigatório antes de produção

A versão atual ainda não faz sincronização bidirecional completa. A Edge Function recebe o evento e o grava em `ingested_events`, mas os demais celulares não recebem automaticamente a alteração.

Antes do APK de produção devem ser implementadas estas duas peças:

1. **Materialização no servidor**
   - transformar eventos ingeridos em registros de `shifts`, `patrol_executions`, `checkpoint_visits`, `occurrences`, `alerts` e alterações administrativas;
   - marcar o evento como `PROCESSED` somente depois da materialização;
   - manter `event_id` único para idempotência.

2. **Pull de configuração e estado compartilhado**
   - endpoint autenticado de snapshot/delta;
   - baixar usuários, pontos, QR ativos, quatro rondas, políticas de tempo e alertas;
   - aplicar tudo em uma transação Room;
   - manter `server_updated_at`/cursor para baixar somente mudanças posteriores nas próximas sincronizações.

Sem essas duas partes, a fila local evita perda de dados, mas não é suficiente para garantir consistência entre vários celulares.

## Conflitos

### Eventos operacionais

Leituras de QR, observações e início/fim de ronda são fatos imutáveis. O servidor não deve sobrescrever um fato por outro: deve aceitar por UUID e rejeitar apenas violações de integridade.

### Configuração

Pontos, QR, porteiros e nomes/horários das quatro rondas usam `updated_at` do servidor. Alterações administrativas devem ocorrer online ou permanecer como `PENDING` até confirmação do servidor.

### Substituição de QR

Ao substituir um QR:

1. o servidor revoga o credential atual;
2. cria nova versão;
3. os aparelhos recebem a alteração no próximo pull;
4. QR antigo passa a gerar `QR_REVOKED`.

Durante uma ronda offline iniciada antes da substituição, a leitura poderá existir localmente. Ao sincronizar, o servidor deve preservar a evidência e marcá-la para auditoria caso o QR já estivesse revogado no horário oficial.

## Política interna de ronda

Os tempos que o condomínio apenas visualiza ficam em `patrol_schedule_policies`:

- tolerância de início;
- tolerância de fim;
- duração prevista;
- intervalo mínimo entre QR Codes.

A tabela permite leitura para o administrador da organização, mas não possui policy de escrita para usuários autenticados. A configuração deve ser alterada apenas pelo backend confiável/service role.

## Alertas

Alertas previstos:

- `QR_READ_TOO_FAST`: menos de 15 segundos entre pontos; a leitura continua válida;
- `PATROL_TOO_EARLY`: início antes da tolerância;
- `PATROL_LATE_START`: início depois da tolerância;
- `PATROL_TOO_LONG`: duração acima do limite;
- `PATROL_LATE_FINISH`: fim após a tolerância;
- `CHECKPOINT_OBSERVATION`: observação do porteiro;
- `CLOCK_CHANGE`: alteração relevante de relógio do aparelho;
- `QR_REVOKED`: tentativa de uso de QR substituído.

## Relatório Excel

O relatório administrativo deve oferecer somente três períodos fixos: 30, 90 e 120 dias.

Campos recomendados:

- data;
- nome da ronda;
- porteiro;
- horário previsto;
- início real;
- fim real;
- duração;
- pontos concluídos/total;
- leituras rápidas;
- observações;
- alertas;
- status da sincronização.

A view `patrol_report_rows` já prepara a consulta central. A geração do `.xlsx` deve ocorrer no backend para evitar bibliotecas pesadas no APK e garantir que o arquivo represente dados já sincronizados de todos os aparelhos.

## Retenção

Não apagar automaticamente leituras, execuções, alertas ou auditoria. Uma política de retenção deve ser definida antes de qualquer limpeza. O aparelho pode remover cache antigo somente depois de confirmação do servidor e mantendo os registros centrais.
