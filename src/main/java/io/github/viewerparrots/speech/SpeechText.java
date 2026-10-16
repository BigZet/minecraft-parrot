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
		return firstWords(text, 30);
	}
	public static int wordCount(String text) { return (int) WORD.matcher(text).results().count(); }
	public static String firstWords(String text, int limit) {
        if (limit < 1) throw new IllegalArgumentException("word limit");
        Matcher words = WORD.matcher(Objects.requireNonNull(text, "text"));
		StringJoiner result = new StringJoiner(" ");
		int count = 0;
		while (count < limit && words.find()) {
			result.add(words.group());
			count++;
		}
		if (count == 0) {
			throw new IllegalArgumentException("Speech text must contain a word.");
		}
		return result.toString();
	}
}
