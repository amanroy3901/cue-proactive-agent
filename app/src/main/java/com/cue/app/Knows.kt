package com.cue.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cue.agent.MeasureWords
import com.cue.agent.PlanSpec
import com.cue.agent.Profile
import com.cue.agent.StandingRules
import com.cue.agent.TextKit
import com.cue.agent.planLine
import com.cue.domain.FactCategory
import com.cue.domain.ProfileFact
import com.cue.ui.*
import kotlinx.coroutines.launch

/**
 * "What Cue knows about you": the profile every call starts with, what Cue picked up and is waiting to keep,
 * the medicines and readings it tracks, and the "when this happens" rules. Everything can be removed here.
 */
@Composable
fun KnowsScreen(a: MainActivity, nav: Nav) {
    val engine = a.cue.engine
    val dao = engine.dao
    val scope = rememberCoroutineScope()
    val facts by dao.factsFlow().collectAsState(emptyList())
    val plans by dao.plansFlow().collectAsState(emptyList())
    val rules by dao.rulesFlow().collectAsState(emptyList())
    val readings by dao.measurementsFlow().collectAsState(emptyList())
    var adding by remember { mutableStateOf("") }
    val zone = java.time.ZoneId.systemDefault()
    val tint = AppTints.albums.deep
    Page(glowA = Cue.glowLavender) {
        item { BackHeader("What Cue knows", "Every call starts with this. Remove anything that's wrong.", { nav.back() }, glow = tint) }

        val pending = facts.filter { !it.confirmed }
        if (pending.isNotEmpty()) {
            item { SectionLabel("Picked up on calls") }
            item {
                LiftCard(Modifier.fillMaxWidth(), tint = tint) {
                    Txt("Keep these?", Type.bodyStrong)
                    Spacer(Modifier.height(4.dp))
                    pending.forEach { f ->
                        FactRow(f, keep = { scope.launch { dao.updateFact(f.copy(confirmed = true)) } }, remove = { scope.launch { dao.deleteFact(f.id) } })
                    }
                }
            }
        }

        val kept = facts.filter { it.confirmed }
        if (kept.isEmpty() && pending.isEmpty()) item { EmptyHint("Tell me things on a call, like \"I have lunch at 1\" or \"I'm allergic to penicillin\", and I'll remember them here.") }
        for (cat in FactCategory.all) {
            val group = kept.filter { it.category == cat }
            if (group.isEmpty()) continue
            item { SectionLabel(categoryTitle(cat)) }
            item {
                LiftCard(Modifier.fillMaxWidth(), tint = tint) {
                    group.forEach { f -> FactRow(f, keep = null, remove = { scope.launch { dao.deleteFact(f.id) } }) }
                }
            }
        }
        item {
            LiftCard(Modifier.fillMaxWidth(), tint = tint) {
                Txt("Tell Cue something", Type.bodyStrong)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Cue.primaryTint).padding(12.dp)) {
                        if (adding.isEmpty()) Txt("I have dinner at 9 pm", Type.body, Cue.muted)
                        BasicTextField(adding, { adding = it }, textStyle = Type.body, modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.width(8.dp))
                    Txt("Save", Type.bodyStrong, Cue.primaryDeep, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                        val text = adding.trim()
                        if (text.isNotEmpty()) scope.launch { val p = Profile(engine); p.remember(text, p.guessCategory(text), source = "user"); adding = "" }
                    }.padding(10.dp))
                }
            }
        }

        val active = plans.filter { PlanSpec.of(it).active }
        if (active.isNotEmpty()) {
            item { SectionLabel("Medicines and readings") }
            item {
                LiftCard(Modifier.fillMaxWidth(), tint = AppTints.reminders.deep) {
                    active.forEach { p ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Txt(planLine(p).substringAfter(' '), Type.body, modifier = Modifier.weight(1f), maxLines = 2)
                            Txt("Stop", Type.small, Cue.coralText, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                                scope.launch { dao.updateItem(p.copy(fields = PlanSpec.of(p).copy(active = false).json())); engine.planner.reconcile() }
                            }.padding(8.dp))
                        }
                    }
                }
            }
        }

        if (readings.isNotEmpty()) {
            item { SectionLabel("Latest readings") }
            item {
                LiftCard(Modifier.fillMaxWidth(), tint = AppTints.renewals.deep) {
                    readings.groupBy { it.kind }.forEach { (kind, list) ->
                        val last = list.first()
                        val avg = list.take(7).map { it.value }.average()
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Column(Modifier.weight(1f)) {
                                Txt(MeasureWords.name(kind).replaceFirstChar { it.uppercase() }, Type.bodyStrong)
                                Txt("${TextKit.dateLabel(last.takenAt, zone)}, ${TextKit.clock(last.takenAt, zone)}${if (list.size > 1) " · ${list.size} readings, avg ${avg.toInt()}" else ""}", Type.small, Cue.muted)
                            }
                            Txt("${MeasureWords.format(kind, last.value, last.value2)} ${last.unit}", Type.bodyStrong, Cue.primaryDeep)
                        }
                    }
                }
            }
        }

        if (rules.isNotEmpty()) {
            item { SectionLabel("When this happens") }
            item {
                LiftCard(Modifier.fillMaxWidth(), tint = AppTints.focus.deep) {
                    rules.forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Txt(StandingRules(engine).describe(r).substringAfter(' ').replaceFirstChar { it.uppercase() }, Type.body, modifier = Modifier.weight(1f), maxLines = 3)
                            Txt("Remove", Type.small, Cue.muted, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { scope.launch { dao.updateRule(r.copy(active = false)) } }.padding(8.dp))
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(120.dp)) }
    }
}

private fun categoryTitle(cat: String) = when (cat) {
    FactCategory.ABOUT -> "About you"
    FactCategory.ROUTINE -> "Your routine"
    FactCategory.PREFERENCE -> "Preferences"
    FactCategory.HEALTH -> "Health"
    FactCategory.PEOPLE -> "People"
    else -> cat
}

@Composable
private fun FactRow(f: ProfileFact, keep: (() -> Unit)?, remove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(f.text, Type.body, modifier = Modifier.weight(1f), maxLines = 3)
        Txt("Remove", Type.small, Cue.muted, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = remove).padding(8.dp))
        if (keep != null) Txt("Keep", Type.bodyStrong, Cue.primaryDeep, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = keep).padding(8.dp))
    }
}
