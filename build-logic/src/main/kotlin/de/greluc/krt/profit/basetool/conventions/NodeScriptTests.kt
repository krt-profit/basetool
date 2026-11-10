package de.greluc.krt.profit.basetool.conventions

import com.github.gradle.node.task.NodeTask
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/** Registers a failing-on-error Node script test with precise caller-owned extra inputs. */
fun Project.registerNodeScriptTest(
  name: String,
  purpose: String,
  scriptPath: String,
  setupTask: String = "npmSetup",
  configure: Action<NodeTask>,
): TaskProvider<NodeTask> =
  tasks.register(
    name,
    NodeTask::class.java,
    Action<NodeTask> {
      group = "verification"
      description = purpose
      dependsOn(tasks.named(setupTask))
      val scriptFile = layout.projectDirectory.file(scriptPath)
      script.set(scriptFile.asFile)
      ignoreExitValue.set(false)
      inputs.file(scriptFile)
      outputs.upToDateWhen { false }
      configure.execute(this)
    },
  )
