#!/bin/bash
cd "$(dirname "$0")"
git add app/src/main/res/layout/activity_main.xml
git commit -m "Fix: Tornar botão de gravação sempre visível com ScrollView

- Envolvi conteúdo em ScrollView para permitir scroll de informações
- Moveu botão para fora do ConstraintLayout para ficar sempre visível
- Botão agora fica em posição fixa na parte inferior da tela

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
git push origin HEAD
