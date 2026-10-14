"use strict";

const form = document.getElementById("speech-form");
const voiceInput = document.getElementById("voice-id");
const presetSelect = document.getElementById("voice-preset");
const modelSelect = document.getElementById("model");
const textInput = document.getElementById("speech-text");
const charCount = document.getElementById("char-count");
const submitButton = document.getElementById("submit-button");
const submitLabel = document.getElementById("submit-label");
const submitIcon = document.getElementById("submit-icon");
const submitSpinner = document.getElementById("submit-spinner");
const requestStatus = document.getElementById("request-status");
const errorMessage = document.getElementById("error-message");
const connection = document.getElementById("connection");
const connectionText = document.getElementById("connection-text");
const keyNote = document.getElementById("key-note");
const emptyResult = document.getElementById("empty-result");
const audioResult = document.getElementById("audio-result");
const player = document.getElementById("audio-player");
const audioNote = document.getElementById("audio-note");
const downloadLink = document.getElementById("download-link");
const resultVoice = document.getElementById("result-voice");
const resultModel = document.getElementById("result-model");

let audioUrl = null;
let generating = false;
let keyConfigured = null;

function updateCount() {
  const length = textInput.value.length;
  charCount.textContent = `${length} / 1000`;
  charCount.dataset.limit = String(length >= 1000);
}

function updateButton() {
  submitButton.disabled = generating || keyConfigured === false;
  submitButton.setAttribute("aria-busy", String(generating));
  submitLabel.textContent = generating ? "Создаём голос…" : "Озвучить";
  submitIcon.hidden = generating;
  submitSpinner.hidden = !generating;
}

function setError(message) {
  errorMessage.textContent = message;
  errorMessage.hidden = !message;
}

function setRequestStatus(message) {
  requestStatus.textContent = message;
  requestStatus.hidden = !message;
}

function syncPreset() {
  const matchingOption = Array.from(presetSelect.options).find(
    (option) => option.value === voiceInput.value.trim()
  );
  presetSelect.value = matchingOption ? matchingOption.value : "";
}

presetSelect.addEventListener("change", () => {
  if (presetSelect.value) {
    voiceInput.value = presetSelect.value;
  } else {
    voiceInput.focus();
  }
});
voiceInput.addEventListener("input", syncPreset);
textInput.addEventListener("input", updateCount);

async function checkKey() {
  try {
    const response = await fetch("/api/status", { cache: "no-store" });
    if (!response.ok) throw new Error("status");
    const status = await response.json();
    if (typeof status.configured !== "boolean") throw new Error("status");
    keyConfigured = status.configured;
    connection.dataset.state = keyConfigured ? "ready" : "missing";
    connectionText.textContent = keyConfigured ? "Ключ подключён" : "Ключ не подключён";
    keyNote.textContent = keyConfigured
      ? "Ключ готов к озвучке. Он хранится на вашем компьютере и не отображается на странице."
      : "Ключ пока не подключён. Подключите его перед озвучкой и обновите страницу.";
  } catch {
    connection.dataset.state = "unknown";
    connectionText.textContent = "Подключение не проверено";
    keyNote.textContent = "Не удалось проверить ключ. Попробуйте озвучить текст или обновите страницу.";
  }
  updateButton();
}

async function readError(response) {
  try {
    const body = await response.json();
    if (typeof body.error === "string" && body.error.trim()) return body.error;
  } catch {
    // A failed response may not contain JSON.
  }
  return `Не удалось создать аудио (код ${response.status}). Попробуйте ещё раз.`;
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  if (generating || keyConfigured === false) return;

  const request = {
    voice_id: voiceInput.value.trim(),
    model: modelSelect.value,
    text: textInput.value.trim(),
  };

  if (!request.voice_id) {
    setError("Введите voice_id или выберите готовый голос.");
    voiceInput.focus();
    return;
  }
  if (!request.text) {
    setError("Напишите реплику для попугая.");
    textInput.focus();
    return;
  }
  if (textInput.value.length > 1000) {
    setError("Для одного теста можно использовать до 1000 символов.");
    textInput.focus();
    return;
  }

  generating = true;
  updateButton();
  setError("");
  setRequestStatus("Озвучиваем реплику. Это может занять немного времени…");

  try {
    const response = await fetch("/api/speech", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(request),
    });
    if (!response.ok) throw new Error(await readError(response));

    const audio = await response.blob();
    if (!audio.size) throw new Error("В ответе нет аудио. Попробуйте озвучить реплику ещё раз.");

    const nextUrl = URL.createObjectURL(audio);
    const previousUrl = audioUrl;
    player.pause();
    player.src = nextUrl;
    downloadLink.href = nextUrl;
    const filenameVoice = request.voice_id.replace(/[^a-zA-Z0-9_-]/g, "-").slice(0, 60) || "voice";
    downloadLink.download = `parrot-${filenameVoice}.mp3`;
    resultVoice.textContent = request.voice_id;
    resultModel.textContent = request.model;
    emptyResult.hidden = true;
    audioResult.hidden = false;
    audioNote.hidden = true;
    audioNote.textContent = "";
    audioUrl = nextUrl;
    if (previousUrl) URL.revokeObjectURL(previousUrl);
    setRequestStatus("Готово. Реплика доступна для прослушивания и скачивания.");

    try {
      await player.play();
    } catch {
      audioNote.textContent = "Нажмите ▶ в плеере, чтобы послушать реплику.";
      audioNote.hidden = false;
    }
  } catch (error) {
    setRequestStatus("");
    setError(error instanceof TypeError
      ? "Не удалось связаться с тестером. Проверьте, что он запущен, и попробуйте ещё раз."
      : error.message || "Не удалось создать аудио. Попробуйте ещё раз.");
  } finally {
    generating = false;
    updateButton();
  }
});

window.addEventListener("beforeunload", () => {
  if (audioUrl) URL.revokeObjectURL(audioUrl);
});

updateCount();
checkKey();
