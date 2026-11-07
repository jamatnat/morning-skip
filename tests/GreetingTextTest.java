package io.github.jamatnat.morningskip;

public final class GreetingTextTest {
    public static void main(String[] args) {
        for (String s : new String[] {"Good morning", "Good afternoon", "Good evening", "Доброе утро", "Добрый день", "Добрый вечер", "GOOD AFTERNOON, Sat 15:03"}) {
            if (!GreetingText.matches(s)) throw new AssertionError(s);
        }
        for (String s : new String[] {null, "", "Incoming call", "Weather", "Alarm"}) {
            if (GreetingText.matches(s)) throw new AssertionError(s);
        }
        System.out.println("Greeting variants: 12 checks passed");
    }
}
