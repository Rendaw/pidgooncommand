package com.zarbosoft.pidgooncommand;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.zarbosoft.interface1.Configuration;
import com.zarbosoft.interface1.Walk;
import com.zarbosoft.pidgoon.AbortParse;
import com.zarbosoft.pidgoon.events.*;
import com.zarbosoft.pidgoon.internal.Helper;
import com.zarbosoft.pidgoon.internal.Node;
import com.zarbosoft.pidgoon.nodes.Sequence;
import com.zarbosoft.pidgoon.nodes.Union;
import com.zarbosoft.rendaw.common.ChainComparator;
import com.zarbosoft.rendaw.common.Pair;
import org.reflections.Reflections;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.zarbosoft.rendaw.common.Common.stream;
import static com.zarbosoft.rendaw.common.Common.uncheck;

public class Command {
	// TODO show default values

	/**
	 * Use in addition to @Configuration to annotate fields as arguments.
	 */
	@Retention(RetentionPolicy.RUNTIME)
	public @interface Argument {
		/**
		 * -1 if not a positional argument, otherwise the unique position of the argument.  Positions do not need
		 * to be contiguous.
		 *
		 * @return
		 */
		int index() default -1;

		/**
		 * Optional short name for a keyword argument.
		 *
		 * @return
		 */
		String shortName() default "";

		/**
		 * Stop parsing when this argument is seen.
		 *
		 * @return
		 */
		boolean earlyExit() default false;
	}

	private static class ArgEvent implements Event {
		public final String value;

		public ArgEvent(final String arg) {
			this.value = arg;
		}

		public ArgEvent() {
			this.value = null;
		}

		public boolean matches(final Event event) {
			if (!(event instanceof ArgEvent))
				return false;
			if (value == null)
				return true;
			return value.equals(((ArgEvent) event).value);
		}

		@Override
		public String toString() {
			return value == null ? "*" : value;
		}
	}

	private static <T> Stream<Pair<Field, T>> streamPositional(
			final Class<?> klass, final List<Pair<Field, T>> fields
	) {
		final Set<Integer> seenIndices = new HashSet<>();
		return fields
				.stream()
				.map(pair -> new Pair<>(pair.first.getAnnotation(Argument.class), pair))
				.filter(pair -> pair.first != null && pair.first.index() >= 0)
				.sorted(new ChainComparator<Pair<Argument, Pair<Field, T>>>()
						.lesserFirst(pair -> pair.first.index())
						.build())
				.map(pair -> {
					final Configuration configuration = pair.second.first.getAnnotation(Configuration.class);
					if (configuration.optional())
						throw new AssertionError(String.format("Positional arguments must not be optional (%s in %s).",
								pair.second.first,
								klass
						));
					if (seenIndices.contains(pair.first.index()))
						throw new AssertionError(String.format("Multiple arguments with index %s in %s.",
								pair.first.index(),
								klass
						));
					return pair.second;
				});
	}

	private static <T> Stream<Pair<Argument, Pair<Field, T>>> streamKeyword(
			final Class<?> klass, final List<Pair<Field, T>> fields
	) {
		final Set<String> seenKeywords = new HashSet<>();
		return fields
				.stream()
				.map(pair -> new Pair<>(pair.first.getAnnotation(Argument.class), pair))
				.filter(pair -> pair.first == null || pair.first.index() < 0)
				.map(pair -> {
					final Argument argument = pair.first;
					final Field field = pair.second.first;
					final Configuration configuration = field.getAnnotation(Configuration.class);
					if (argument != null && !argument.shortName().isEmpty()) {
						if (seenKeywords.contains(argument.shortName()))
							throw new AssertionError(String.format("Duplicate keyword identifier [%s] in %s.",
									argument.shortName(),
									klass
							));
						seenKeywords.add(argument.shortName());
					}
					final String longName = Walk.decideName(field);
					if (seenKeywords.contains(longName))
						throw new AssertionError(String.format("Duplicate keyword identifier [%s] in %s.",
								longName,
								klass
						));
					return pair;
				});
	}

	public static void showHelp(
			final Reflections reflections, final Class<?> rootClass, final String usagePrefix
	) {
		class Line {
			int indent;
			String text;

			public Line(final String text) {
				indent = 0;
				this.text = text;
			}

			public Line(final int indent, final String text) {
				this.indent = indent;
				this.text = text;
			}

			public Line indent() {
				return new Line(indent + 1, text);
			}
		}
		final Map<Class<?>, Iterable<Line>> seenAbstract = new HashMap();
		Walk.walk(reflections, rootClass, new Walk.Visitor<Iterable<Line>>() {

			@Override
			public Iterable<Line> visitString(final Field field) {
				return ImmutableList.of(new Line("Any string"));
			}

			@Override
			public Iterable<Line> visitInteger(final Field field) {
				return ImmutableList.of(new Line("Any integer"));
			}

			@Override
			public Iterable<Line> visitDouble(final Field field) {
				return ImmutableList.of(new Line("Any double"));
			}

			@Override
			public Iterable<Line> visitBoolean(final Field field) {
				final Argument argument = field == null ? null : field.getAnnotation(Argument.class);
				if (argument != null && argument.index() >= 0)
					return ImmutableList.of(new Line("true"), new Line("false"));
				return ImmutableList.of();
			}

			@Override
			public Iterable<Line> visitEnum(final Field field, final Class<?> enumClass) {
				return Walk
						.enumValues(enumClass)
						.stream()
						.map(pair -> new Line(Walk.decideName(pair.second)))
						.collect(Collectors.toList());
			}

			@Override
			public Iterable<Line> visitList(final Field field, final Iterable<Line> inner) {
				return Iterables.concat(ImmutableList.of(new Line("(may be specified multiple times)")), inner);
			}

			@Override
			public Iterable<Line> visitSet(final Field field, final Iterable<Line> inner) {
				return Iterables.concat(ImmutableList.of(new Line("(may be specified multiple times)")), inner);
			}

			@Override
			public Iterable<Line> visitMap(final Field field, final Iterable<Line> inner) {
				return Stream.concat(
						Stream.of(new Line("KEY VALUE"),
								new Line("where KEY is any string."),
								new Line("where VALUE is:")
						),
						stream(inner).map(line -> line.indent())
				).collect(Collectors.toList());
			}

			@Override
			public Iterable<Line> visitAbstractShort(final Field field, final Class<?> klass) {
				return seenAbstract.get(klass);
			}

			@Override
			public Iterable<Line> visitAbstract(
					final Field field, final Class<?> klass, final List<Pair<Class<?>, Iterable<Line>>> derived
			) {
				final Iterable<Line> out = derived
						.stream()
						.map(pair -> new Line(Walk.decideName(pair.first)))
						.collect(Collectors.toList());
				seenAbstract.put(klass, out);
				return out;
			}

			@Override
			public Iterable<Line> visitConcreteShort(final Field field, final Class<?> klass) {
				return null;
			}

			@Override
			public Iterable<Line> visitConcrete(
					final Field field, final Class<?> klass, final List<Pair<Field, Iterable<Line>>> fields
			) {
				final Stream<Line> positionalStream = streamPositional(klass, fields).flatMap(positional -> {
					final Configuration configuration =
							uncheck(() -> positional.first.getAnnotation(Configuration.class));
					Stream<Line> name = Stream.of(new Line(""), new Line(Walk.decideName(positional.first)));
					Stream<Line> description = configuration.description().isEmpty() ?
							Stream.empty() :
							Stream.<Line>of(new Line(configuration.description()));
					Stream<Line> values = Stream
							.of(Stream.of(new Line("Value:")),
									stream(positional.second).<Line>map(line -> line.indent())
							)
							.flatMap(l -> l);
					return Stream.of(name,
							Stream.of(new Line("")),
							Stream.concat(description, values).map(line -> line.indent())
					).flatMap(l -> l);
				}).map(line -> line.indent().indent());
				final boolean hasKeywords = streamKeyword(klass, fields).anyMatch(x -> true);
				final Stream<Line> keywordStream = !hasKeywords ?
						Stream.empty() :
						Stream.of(Stream.of(new Line(""), new Line("KEYWORD ARGUMENTS")),
								streamKeyword(klass, fields).flatMap(keyword -> {
									final Argument argument = keyword.first;
									final Field field2 = keyword.second.first;
									final Configuration configuration =
											uncheck(() -> field2.getAnnotation(Configuration.class));
									Stream<Line> name = Stream.of(new Line(""), new Line(String.format("%s%s%s",
											Walk.decideName(field2),
											(argument == null || argument.shortName().isEmpty()) ?
													"" :
													", " + argument.shortName(),
											configuration.optional() ? " (optional)" : ""
									)));
									Stream<Line> description = configuration.description().isEmpty() ?
											Stream.empty() :
											Stream.of(new Line(configuration.description()));
									Stream<Line> values = !keyword.second.second.iterator().hasNext() ?
											null :
											Stream.of(Stream.of(new Line("Value:")),
													stream(keyword.second.second).<Line>map(line -> line.indent())
											).flatMap(l -> l);
									return Stream.<Stream<Line>>of(name,
											description == null && values == null ?
													Stream.empty() :
													Stream.of(Stream.of(new Line("")), (Stream<Line>) (
															description == null ? Stream.empty() : description
													), (Stream<Line>) (
															values == null ? Stream.empty() : values
													)).flatMap(l -> l).map(line -> line.indent())
									).flatMap(l -> l);
								}).map(line -> line.indent().indent())
						).flatMap(l -> l);
				return Stream.of(Stream.of(new Line(String.format("%s%s%s",
						klass == rootClass ? usagePrefix : "",
						streamPositional(klass, fields)
								.map(pair -> Walk.decideName(pair.first))
								.collect(Collectors.joining(" ")),
						hasKeywords ? " [keyword arguments]" : ""
				))), positionalStream, keywordStream).flatMap(l -> l).collect(Collectors.toList());
			}
		}).forEach(line -> System.out.format("%s%s\n", Strings.repeat("  ", line.indent), line.text));
		System.out.flush();
	}

	public static <T> T parse(final Reflections reflections, final Class<T> klass, final String[] args) {
		final Grammar grammar = new Grammar();
		final Map<Class<?>, Node> seenAbstract = new HashMap<>();
		final Map<Class<?>, Node> seenConcrete = new HashMap<>();
		grammar.add("root", Walk.walk(reflections, klass, new Walk.Visitor<Node>() {
			@Override
			public Node visitString(final Field field) {
				return new BakedOperator(new Terminal(new ArgEvent()), store -> {
					return store.pushStack(((ArgEvent) store.top()).value);
				});
			}

			@Override
			public Node visitInteger(final Field field) {
				return new BakedOperator(new Terminal(new ArgEvent()), store -> {
					final ArgEvent event = (ArgEvent) store.top();
					try {
						return store.pushStack(Integer.parseInt(event.value));
					} catch (final NumberFormatException e) {
						throw new AbortParse(String.format("%s is not an integer.", event.value));
					}
				});
			}

			@Override
			public Node visitDouble(final Field field) {
				return new BakedOperator(new Terminal(new ArgEvent()), store -> {
					final ArgEvent event = (ArgEvent) store.top();
					try {
						return store.pushStack(Double.parseDouble(event.value));
					} catch (final NumberFormatException e) {
						throw new AbortParse(String.format("%s is not a double.", event.value));
					}
				});
			}

			@Override
			public Node visitBoolean(final Field field) {
				final Argument argument = field == null ? null : field.getAnnotation(Argument.class);
				if (argument != null && argument.index() >= 0)
					return new BakedOperator(new Terminal(new ArgEvent()), store -> {
						final ArgEvent event = (ArgEvent) store.top();
						try {
							return store.pushStack(Boolean.parseBoolean(event.value));
						} catch (final NumberFormatException e) {
							throw new AbortParse(String.format("%s is not a boolean.", event.value));
						}
					});
				else
					return new BakedOperator(store -> {
						return store.pushStack(true);
					});
			}

			@Override
			public Node visitEnum(final Field field, final Class<?> enumClass) {
				final Union union = new Union();
				Walk.enumValues(enumClass).stream().forEach(pair -> {
					union.add(new BakedOperator(new Terminal(new ArgEvent(Walk.decideName(pair.second))), store -> {
						return store.pushStack(pair.first);
					}));
				});
				return union;
			}

			@Override
			public Node visitList(final Field field, final Node inner) {
				return inner;
			}

			@Override
			public Node visitSet(final Field field, final Node inner) {
				return inner;
			}

			@Override
			public Node visitMap(final Field field, final Node inner) {
				return new Sequence()
						.add(new Terminal(new ArgEvent()))
						.add(new BakedOperator(new Terminal(new ArgEvent()), store -> {
							return store.pushStack(Double.parseDouble(((ArgEvent) store.top()).value));
						}));
			}

			@Override
			public Node visitAbstractShort(final Field field, final Class<?> klass) {
				return seenAbstract.get(klass);
			}

			@Override
			public Node visitAbstract(
					final Field field, final Class<?> klass, final List<Pair<Class<?>, Node>> derived
			) {
				final Union union = new Union();
				derived.forEach(pair -> union.add(new Sequence()
						.add(new Terminal(new ArgEvent(Walk.decideName(pair.first))))
						.add(pair.second)));
				seenAbstract.put(klass, union);
				return union;
			}

			@Override
			public Node visitConcreteShort(final Field field, final Class<?> klass) {
				return seenConcrete.get(klass);
			}

			@Override
			public Node visitConcrete(final Field field, final Class<?> klass, final List<Pair<Field, Node>> fields) {
				final Union root = new Union();
				final Sequence positional = new Sequence();
				streamPositional(klass, fields).forEach(pair -> {
					positional.add(new BakedOperator(pair.second, store -> {
						store = (Store) store.pushStack(pair.first);
						return Helper.stackDoubleElement(store);
					}));
				});
				final com.zarbosoft.pidgoon.nodes.Set keyword = new com.zarbosoft.pidgoon.nodes.Set();
				streamKeyword(klass, fields).forEach(pair -> {
					final Argument argument = pair.first;
					final Field field2 = pair.second.first;
					final Configuration configuration = field2.getAnnotation(Configuration.class);
					final Node node = pair.second.second;
					final Union out = new Union();
					if (argument != null && !argument.shortName().isEmpty()) {
						out.add(new Sequence()
								.add(new Terminal(new ArgEvent(argument.shortName())))
								.add(new BakedOperator(node, store -> {
									store = (Store) store.pushStack(field2);
									return Helper.stackDoubleElement(store);
								})));
					}
					final String longName = Walk.decideName(field2);
					out.add(new Sequence()
							.add(new Terminal(new ArgEvent(longName)))
							.add(new BakedOperator(node, store -> {
								store = (Store) store.pushStack(field2);
								return Helper.stackDoubleElement(store);
							})));
					if (argument != null && argument.earlyExit())
						root.add(out);
					else
						keyword.add(out, !configuration.optional());
				});
				positional.add(keyword);
				root.add(positional);
				final Node rule = new Sequence()
						.add(new BakedOperator(store -> store.pushStack(0)))
						.add(new BakedOperator(root, store -> {
							final Object out = uncheck(klass::newInstance);
							final java.util.Set<Field> fields2 = new HashSet<>();
							fields2.addAll(fields.stream().map(pair -> pair.first).collect(Collectors.toList()));
							store = (Store) Helper.<Pair<Object, Field>>stackPopSingleList(store, (pair) -> {
								fields2.remove(pair.second);
								uncheck(() -> {
									if (Collection.class.isAssignableFrom(pair.second.getType())) {
										Collection collection = (Collection) pair.second.get(out);
										if (collection == null) {
											if (List.class.isAssignableFrom(pair.second.getType()))
												collection = new ArrayList();
											else if (Set.class.isAssignableFrom(pair.second.getType()))
												collection = new HashSet();
											else
												throw new AssertionError(String.format("Can't handle collection type %s.",
														pair.second.getType()
												));
											pair.second.set(out, collection);
										}
										collection.add(pair.first);
									} else if (Map.class.isAssignableFrom(pair.second.getType())) {
										Map map = (Map) pair.second.get(out);
										if (map == null) {
											map = new HashMap();
											pair.second.set(out, map);
										}
										map.put(((Pair<String, Object>) pair.first).first,
												((Pair<String, Object>) pair.first).second
										);
									} else
										pair.second.set(out, pair.first);
								});
							});
							return store.pushStack(out);
						}));
				seenConcrete.put(klass, rule);
				return rule;
			}
		}));
		EventStream<T> stream = new Parse<T>().grammar(grammar).parse();
		for (int i = 0; i < args.length; ++i)
			stream = stream.push(new ArgEvent(args[i]), String.format("arg %s", i + 1));
		return stream.finish();
	}
}
