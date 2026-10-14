package io.github.viewerparrots.speech;

import java.util.Objects;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Limits speech by words without changing the viewer's spelling or punctuation. */
public final class SpeechText {
	private static final Pattern WORD = Pattern.compile("\\S+", Pattern.UNICODE_CHARACTER_CLASS);

	private SpeechText() { }

	public static String firstThirtyWords(String text) {
		Matcher words = WORD.matcher(Objects.requireNonNull(text, "text"));
		StringJoiner result = new StringJoiner(" ");
		int count = 0;
		while (count < 30 && words.find()) {
			result.add(words.group());
			count++;
		}
		if (count == 0) {
			throw new IllegalArgumentException("Speech text must contain a word.");
		}
		return result.toString();
	}
}
