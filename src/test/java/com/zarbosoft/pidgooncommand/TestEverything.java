package com.zarbosoft.pidgooncommand;

import com.zarbosoft.interface1.Configuration;
import org.junit.Test;
import org.reflections.Reflections;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertThat;

public class TestEverything {

	@Configuration(description = "This is an embedded class.")
	public static class SubCommand1 {
		@Command.Argument(index = 0)
		@Configuration(name = "A")
		public int a;
		@Configuration(name = "B", optional = true)
		public int b = 44;
	}

	@Configuration(description = "The princess has abandoned the castle.")
	public static abstract class SubCommand2Base {

	}

	@Configuration(name = "xa")
	public static class SubCommand2A extends SubCommand2Base {
		@Command.Argument(index = 0)
		@Configuration(name = "A")
		public int a;

	}

	@Configuration(name = "xb")
	public static class SubCommand2B extends SubCommand2Base {
		@Command.Argument(index = 0)
		@Configuration(name = "A")
		public int a;
	}

	@Configuration
	public static class CommandLine {
		@Command.Argument(index = 0)
		@Configuration(name = "ARG_A", description = "Testing")
		public int a;
		@Command.Argument(index = 1)
		@Configuration(name = "ARG_B", description = "Must be true.")
		public boolean b;
		@Command.Argument(index = 2)
		@Configuration(name = "ARG_C", description = "Not sure what this does.")
		public double c;
		@Command.Argument(index = 3)
		@Configuration(name = "DANGEROUS", description = "Any string.")
		public String d;
		@Command.Argument(index = 4)
		@Configuration(name = "GENERAL")
		public SubCommand1 sub;
		@Command.Argument(index = 5)
		@Configuration(name = "SUBCOMMAND")
		public SubCommand2Base sub2;
		@Command.Argument(shortName = "-u")
		@Configuration(name = "--ultima", description = "Default is 7.", optional = true)
		public int e = 7;
		@Command.Argument(shortName = "-f")
		@Configuration(name = "--f")
		public boolean f = false;
		@Configuration(name = "--g", optional = true)
		public double g;
		@Configuration(description = "No description", optional = true)
		public String h;
		@Configuration(name = "--out-of-names", optional = true)
		public SubCommand2Base sub3;
	}

	@Test
	public void parse() {
		final CommandLine out =
				Command.parse(new Reflections("com.zarbosoft.pidgooncommand"), CommandLine.class, new String[] {
						"4",
						"true",
						"3.3",
						"waffel",
						"7",
						"B",
						"47",
						"xb",
						"12",
						"-f",
						"--out-of-names",
						"xa",
						"2384897"
				});
		assertThat(out.a, equalTo(4));
		assertThat(out.b, equalTo(true));
		assertThat(out.c, equalTo(3.3));
		assertThat(out.d, equalTo("waffel"));
		assertThat(out.sub.a, equalTo(7));
		assertThat(out.sub.b, equalTo(47));
		assertThat(out.sub2, instanceOf(SubCommand2B.class));
		assertThat(((SubCommand2B) out.sub2).a, equalTo(12));
		assertThat(out.e, equalTo(7));
		assertThat(out.f, equalTo(true));
		assertThat(out.sub3, instanceOf(SubCommand2A.class));
		assertThat(((SubCommand2A) out.sub3).a, equalTo(2384897));
	}

	@Test
	public void rootHelp() {
		Command.showHelp(new Reflections("com.zarbosoft.pidgooncommand"), CommandLine.class, "test usage: ");
	}
}
