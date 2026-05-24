package org.opengb.observability

import org.apache.logging.log4j.message.StringMapMessage

/**
 * Base class for typed, structured log messages. Each concrete subclass declares its fields as
 * constructor parameters and registers them via [field] in its `init` block. Because the base
 * extends log4j2's [StringMapMessage], the `map` resolver in `OpenGbEcsLayout.json` flattens
 * those entries into top-level fields of the rendered JSON event.
 *
 * Override [humanMessage] to provide the short, single-line summary that goes into the JSON
 * `message` field. The structured fields land alongside it as siblings, not inside it.
 *
 * Typical pattern (modelled on Bootable's idiom):
 *
 * ```
 * class MyMessage(val name: String, val count: Int) : StructuredLogMessage() {
 *     init {
 *         field("user.name", name)
 *         field("event.count", count)
 *     }
 *     override val humanMessage: String get() = "MyMessage: $name x $count"
 *     fun log() = myLogger.info(this)
 * }
 * ```
 */
abstract class StructuredLogMessage : StringMapMessage() {
  /** Short single-line summary; rendered into the JSON `message` field. */
  protected abstract val humanMessage: String

  final override fun getFormattedMessage(): String = humanMessage

  // JsonTemplateLayout's message resolver short-circuits to formatTo() when the message
  // implements StringBuilderFormattable (which MapMessage does), bypassing
  // getFormattedMessage(). Override both so either code path renders the human message.
  final override fun formatTo(buffer: StringBuilder) {
    buffer.append(humanMessage)
  }

  /** Convenience for subclasses: skip null values so the rendered map stays clean. */
  protected fun field(
    key: String,
    value: Any?,
  ) {
    if (value != null) put(key, value.toString())
  }
}
