#!/usr/bin/env bash
# Установка JDK 17 для сборки Seeker_Mining (и других Android/Java проектов).
# Запуск: bash scripts/install-jdk17.sh
# После установки открой новый терминал или выполни: source ~/.zprofile

set -e
echo "=== Установка JDK 17 ==="

# 1) SDKMAN уже установлен — ставим Java через него
if [[ -s "$HOME/.sdkman/bin/sdkman-init.sh" ]]; then
  echo "Используем SDKMAN..."
  source "$HOME/.sdkman/bin/sdkman-init.sh"
  sdk install java 17.0.9-tem || true
  sdk default java 17.0.9-tem 2>/dev/null || true
  echo "Готово. Открой новый терминал или выполни: source ~/.zprofile"
  exit 0
fi

# 2) Если нет SDKMAN — предложить Homebrew (нужны права на /opt/homebrew)
if command -v brew &>/dev/null; then
  echo "SDKMAN не найден. Можно установить JDK через Homebrew:"
  echo "  brew install openjdk@17"
  echo "Если появится ошибка прав доступа, выполни:"
  echo "  sudo chown -R \$(whoami) /opt/homebrew /opt/homebrew/Cellar"
  echo "и снова запусти: brew install openjdk@17"
  echo ""
  echo "После установки JAVA_HOME подхватится из ~/.zprofile (вариант 2)."
  exit 0
fi

echo "Установи SDKMAN: curl -s \"https://get.sdkman.io\" | bash"
echo "Затем снова запусти этот скрипт."
exit 1
