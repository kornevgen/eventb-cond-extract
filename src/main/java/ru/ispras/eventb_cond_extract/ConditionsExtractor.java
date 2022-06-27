package ru.ispras.eventb_cond_extract;

import java.io.PrintStream;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eventb.core.ast.*;

/**
 * Extracts all conditions from the Rodin Event-B model.
 * The model must be stored as one statically checked machine
 * in the *.bcm file (generated by Rodin).
 * To get the conditions you should instantiate this class
 * and use the field {@link conditions}. You may use
 * the field {@link scMachine} to get access to the machine
 * (to iterate its elements, for example, events). And you
 * may use the field {@link typeEnvironments} for parsing
 * and type-checking the formulas of the machine.
 *
 * Conditions identifiers are formed as &lt;guard-name&gt;/&lt;index&gt;.
 * Each condition in the guard has its own index. index
 * equal to 0 is reserved for the full guard. Conditions
 * indexes are started from 1 and increased continuously
 * by depth-first visiting.
 * 
 * Here is the list of operators which are splitted and when:
 * <ul>
 * <li>conjunction (always)</li>
 * <li>disjunction (always)</li>
 * <li>implication (always)</li>
 * <li>equivalence (always)</li>
 * <li>negation (always)</li>
 * <li>relations: not equal, less, less than, greater, greater than,
 * 	contains, not contains, is subset, is subset or equal,
 * 	is not subset, is not subset or equal (are splitted
 * 	only if there are one of the part in the same event)</li>
 * </ul>
 *
 * When a guard contains several identical conditions,
 * only the first condition is numerated. Two conditions are identical
 * if they are the same after negation of one condition or after
 * changing the order of the operands of one condition. For example, the predicate
 * <code>((a = b) =&gt; (c /= a)) &amp; ((a /= b) =&gt; (a = c)))</code>
 * has the following conditions only:
 * <ol>
 * <li><code>a = b</code></li>
 * <li><code>c /= a</code></li>
 * </ol>
 *
 * For each set of identical conditions only the first occurred condition
 * in the model is stored and indexed. Other conditions are not indexed. 
 */
public class ConditionsExtractor {

	/**
	 * Statically checked machine used for conditions extraction.
	 */
	public final StaticallyCheckedMachine scMachine;

	/**
	 * Type environments of the machine.
	 */
	public final TypeEnvironmentsHolder typeEnvironments;

	/**
	 * Conditions of the machine.
	 */
	public final Conditions conditions;

	/**
	 *
	 * @param scMachine	statically checked machine for conditions extraction
	 * @throws IllegalArgumentException	if scMachine has incorrect predicate
	 */
	public ConditionsExtractor(final StaticallyCheckedMachine scMachine) 
	{
		this.scMachine = scMachine;
		this.typeEnvironments = new TypeEnvironmentsHolder(scMachine);
		this.conditions = computeConditions().build();
	}

	private Conditions.Builder computeConditions() {
		final Conditions.Builder conditionsBuilder = new Conditions.Builder();

		scMachine.events.forEach(event -> {
			final Map<StaticallyCheckedGuard, List<Predicate>> predicates = new HashMap<>();
			computeEventConditions(event, predicates);
			
			final ITypeEnvironment typeEnvironment = typeEnvironments.eventsTypeEnvironments.get(event.label);
			final Map<String, Condition> event_conditions = new HashMap<>();
			final List<String> event_conditions_order = new ArrayList<>();
			event.guards.forEach(guard -> {
				final List<Predicate> p = predicates.get(guard);
				for (int i = 0; i < p.size(); ++i) {
					final Predicate c = p.get(i);
					final String condition_id = String.format("%s/%d", guard.label, i + 1);
					final String wdPredicate = computeWDPredicate(c, typeEnvironment);
					event_conditions_order.add(condition_id);
					event_conditions.put(condition_id, new Condition(condition_id, c, wdPredicate));
				}
			});
			conditionsBuilder.conditions.put(event.label, event_conditions);
			conditionsBuilder.conditions_order.put(event.label, event_conditions_order);
		});

		return conditionsBuilder;
	}

	private void computeEventConditions(
			final StaticallyCheckedEvent event,
			final /*Out*/ Map<StaticallyCheckedGuard, List<Predicate>> conditions)
	{
		event.guards.forEach(guard -> {
			final List<Predicate> predicates = new ArrayList<>();
			computeGuardConditions(guard, predicates);
			conditions.put(guard, predicates);
		});
		removeIdenticalPredicates(event, conditions);
	}

	private void computeGuardConditions(
			final StaticallyCheckedGuard guard,
			final /*Out*/ List<Predicate> conditions)
	{
			final IParseResult parsedFormula = typeEnvironments.ff.parsePredicate(guard.predicate, null);
			final Predicate predicate = parsedFormula.getParsedPredicate();
			if (parsedFormula.hasProblem()) {
				throw new IllegalArgumentException(
					Stream.concat(
						Stream.of("Cannot parse guard " + guard.label + ": " + guard.predicate + ":"),
						parsedFormula.getProblems().stream().map(Object::toString))
					.collect(Collectors.joining(System.lineSeparator())));
			}
			computePredicateConditions(predicate, conditions);
	}

	private void computePredicateConditions(
			final Predicate predicate,
			final /*Out*/ List<Predicate> conditions)
	{
		final Deque<Predicate> queue = new LinkedList<>();
		queue.addFirst(predicate);

		while (!queue.isEmpty()) {
			final Predicate p = queue.removeFirst();
			if (p instanceof BinaryPredicate) {
				// left => right
				// left <=> right
				queue.addFirst(((BinaryPredicate) p).getRight());
				queue.addFirst(((BinaryPredicate) p).getLeft());
				continue;
			} else if (p instanceof AssociativePredicate) {
				// child & child & child ... child
				// child or child or child ... child
				final AssociativePredicate ap = (AssociativePredicate) p;
				for (int i = ap.getChildCount() - 1; i >= 0; --i) {
					queue.addFirst(ap.getChild(i));
				}
				continue;
			} else if (p instanceof UnaryPredicate) {
				// not child
				queue.addFirst(((UnaryPredicate) p).getChild());
				continue;
			} else if (p instanceof RelationalPredicate) {
				// left = right
				// left /= right
				// left < right
				// left <= right
				// left > right
				// left >= right
				// left : right
				// left /: right
				// left <: right
				// left <<: right
				// left /<: right
				// left /<<: right
				final RelationalPredicate r = (RelationalPredicate) p;
				final Expression left = r.getLeft();
				final Expression right = r.getRight();
				switch (p.getTag()) {
					case Formula.EQUAL:
					case Formula.NOTEQUAL:
					case Formula.LT:
					case Formula.LE:
					case Formula.GT:
					case Formula.GE:
						break;
					case Formula.IN:
					case Formula.NOTIN:
						if (right instanceof SetExtension) {
							for (final Expression member: ((SetExtension) right).getMembers()) {
								queue.addFirst(p.getFactory().makeRelationalPredicate(
											Formula.EQUAL,
											left,
											member,
											p.getSourceLocation()));
							}
							continue;
						} else if (right instanceof AssociativeExpression) {
							switch (right.getTag()) {
								case Formula.BUNION: case Formula.BINTER:
									for (final Expression child: ((AssociativeExpression) right).getChildren()) {
										queue.addFirst(p.getFactory().makeRelationalPredicate(
													Formula.IN,
													left,
													child,
													p.getSourceLocation()));
									}
									continue;
								default:
									break;
							}
						} else {
							break;
						}
					case Formula.SUBSETEQ:
					case Formula.NOTSUBSETEQ:
						if (left instanceof SetExtension) {
							for (final Expression member: ((SetExtension) left).getMembers()) {
								queue.addFirst(p.getFactory().makeRelationalPredicate(
											Formula.IN,
											member,
											right,
											p.getSourceLocation()));
							}
							continue;
						} else {
							break;
						}
					case Formula.SUBSET:
					case Formula.NOTSUBSET:
					default:
						break;
				}
			}

			conditions.add(p);
		}
	}

	private void removeIdenticalPredicates(final StaticallyCheckedEvent event, final Map<StaticallyCheckedGuard, List<Predicate>> predicates)
	{
		final List<Predicate> allPredicates = new ArrayList<>();
		final Map<Predicate, StaticallyCheckedGuard> predicatesToGuard = new HashMap<>();
		final Map<Predicate, String> normalizedPredicates = new HashMap<>();
		event.guards.forEach(guard -> {
			final Set<Predicate> toRemove = new HashSet<>();
			predicates.get(guard).forEach(predicate -> {
				if (allPredicates.contains(predicate)) {
					toRemove.add(predicate);
				} else {
					allPredicates.add(predicate);
					predicatesToGuard.put(predicate, guard);
					normalizedPredicates.put(predicate, getNormalizedPredicate(predicate).toString());
				}
			});
			predicates.get(guard).removeAll(toRemove);
		});
		
		for (int i = 0; i < allPredicates.size(); ++i) {
			final Predicate p = allPredicates.get(i);
			final String n = normalizedPredicates.get(p);
			final StaticallyCheckedGuard g = predicatesToGuard.get(p);

			for (int j = 0; j < i; ++j) {
				final Predicate p2 = allPredicates.get(j);
				final String n2 = normalizedPredicates.get(p2);
				if (n.equals(n2)) {
					predicates.get(g).remove(p);
					break;
				}
			}
		}
	}

	private String computeWDPredicate(
			final Predicate predicate,
			final ITypeEnvironment typeEnvironment)
	{
		if (!predicate.isTypeChecked()) {
			final ITypeCheckResult tc = predicate.typeCheck(typeEnvironment);
			if (tc.hasProblem()) {
				throw new IllegalArgumentException(
					Stream.concat(
						Stream.of("Cannot type-check predicate: " + predicate.toString()),
						tc.getProblems().stream().map(Object::toString))
					.collect(Collectors.joining(System.lineSeparator())));
			}
		}
		return predicate.getWDPredicate().toString();
	}

	private Predicate getNormalizedPredicate(final Predicate predicate)
	{
		if (predicate instanceof RelationalPredicate) {
		
			final RelationalPredicate p = (RelationalPredicate) predicate;
			final Expression left = p.getLeft();
			final Expression right = p.getRight();
			switch (p.getTag()) {
				case Formula.EQUAL:
				case Formula.NOTEQUAL:
				case Formula.LT:
				case Formula.LE:
				case Formula.GT:
				case Formula.GE:
					if (left.toString().compareTo(right.toString()) > 0) {
						return getNormalizedPredicate(p.getFactory().makeRelationalPredicate(
									getInvertedTag(p.getTag()),
									right,
									left,
									p.getSourceLocation()));
					} else if (p.getTag() == Formula.NOTEQUAL || p.getTag() == Formula.GT || p.getTag() == Formula.GE) {
						return getNormalizedPredicate(p.getFactory().makeRelationalPredicate(
									getNegatedTag(p.getTag()),
									left,
									right,
									p.getSourceLocation()));
					} else {
						break;
					}
				case Formula.IN:
				case Formula.SUBSETEQ:
				case Formula.SUBSET:
					break;
				case Formula.NOTIN:
				case Formula.NOTSUBSETEQ:
				case Formula.NOTSUBSET:
					return getNormalizedPredicate(p.getFactory().makeRelationalPredicate(
								getNegatedTag(p.getTag()),
								left,
								right,
								p.getSourceLocation()));
				default:
					break;
			}
		}
	
		return predicate;
	}

	private int getInvertedTag(int tag)
	{
		switch (tag) {
			case Formula.LT:
				return Formula.GT;
			case Formula.LE:
				return Formula.GE;
			case Formula.GT:
				return Formula.LT;
			case Formula.GE:
				return Formula.LE;
			default:
				return tag;
		}
	}

	private int getNegatedTag(int tag)
	{
		switch (tag) {
			case Formula.EQUAL:
				return Formula.NOTEQUAL;
			case Formula.NOTEQUAL:
				return Formula.EQUAL;
			case Formula.LT:
				return Formula.GE;
			case Formula.LE:
				return Formula.GT;
			case Formula.GT:
				return Formula.LE;
			case Formula.GE:
				return Formula.LT;
			case Formula.IN:
				return Formula.NOTIN;
			case Formula.SUBSETEQ:
				return Formula.NOTSUBSETEQ;
			case Formula.SUBSET:
				return Formula.NOTSUBSET;
			case Formula.NOTIN:
				return Formula.IN;
			case Formula.NOTSUBSETEQ:
				return Formula.SUBSETEQ;
			case Formula.NOTSUBSET:
				return Formula.SUBSET;
			default:
				return tag;
		}
	}

	/**
	 * Prints the conditions.
	 * It prints the list of conditions identifiers and
	 * conditions predicates for each event.
	 * The order of events is gotten from the machine.
	 *
	 * @param out	stream to output the conditions
	 */
	public void printConditions(final PrintStream out) {
		scMachine.events.stream().forEach(scEvent -> {
			out.println(scEvent.label);
			conditions.conditions_order.get(scEvent.label).stream().forEach(condition_id -> {
				final String predicate = conditions.conditions.get(scEvent.label).get(condition_id).predicate.toString();
				out.println(" - [" + condition_id + "] " + predicate);
			});
			out.println();
		});
	}

}