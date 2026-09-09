# Segurança

- PINs usam PBKDF2-HMAC-SHA256 com salt individual e comparação em tempo constante.
- Cinco tentativas inválidas bloqueiam o perfil localmente por cinco minutos e geram alerta.
- Segredos de dispositivo são protegidos por AES-GCM com chave não exportável do Android Keystore.
- QR Codes carregam um segredo aleatório; somente SHA-256 do valor completo é persistido.
- QR revogado, ponto inativo e dispositivo não autorizado bloqueiam a confirmação.
- Horário de parede é comparado ao relógio monotônico para sinalizar alterações durante a ronda.
- Alertas antifraude não descartam evidência e não bloqueiam casos apenas suspeitos.
- RLS isola organizações e limita mutações diretas a administradores autenticados.
- A Edge Function usa `service_role` somente no servidor e exige ID e token próprios do aparelho.
- Registros de ronda, visita, auditoria e ingestão não podem ser excluídos via usuário autenticado.

Nunca inclua `SUPABASE_SERVICE_ROLE_KEY`, PIN puro ou token de dispositivo no aplicativo ou no Git.
