package fr.acinq.eclair

import java.io.File
import java.net.{URL, URLClassLoader}

import org.clapper.classutil.ClassFinder

trait Plugin {

  def onSetup(setup: Setup): Unit

  def onKit(kit: Kit): Unit

}

object Plugin {

  def loadPlugins(jars: Seq[File]): Seq[Plugin] = {
      val finder = ClassFinder(jars)
      val classes = finder.getClasses
      val urls = jars.map(f => new URL(s"file:${f.getCanonicalPath}"))
      val loader = new URLClassLoader(urls.toArray, ClassLoader.getSystemClassLoader)
      classes
        .filter(_.isConcrete)
        .filter(_.implements(classOf[Plugin].getName))
        .map(c => Class.forName(c.name, true, loader).getDeclaredConstructor().newInstance().asInstanceOf[Plugin])
        .toList
    }
}