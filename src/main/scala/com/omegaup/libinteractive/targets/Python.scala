package com.omegaup.libinteractive.target

import java.nio.file.Path
import java.nio.file.Paths

import scala.collection.mutable.StringBuilder

import com.omegaup.libinteractive.idl._

class Python(idl: IDL, options: Options, input: Path, parent: Boolean)
		extends Target(idl, options) {
	override def generate() = {
		if (parent) {
			val mainFile = s"${idl.main.name}.py"
			List(
				new OutputDirectory(Paths.get(idl.main.name)),
				new OutputLink(Paths.get(idl.main.name, mainFile), input),
				generateMain,
				generateMainEntry)
		} else {
			val moduleFile = s"${options.moduleName}.py"
			idl.interfaces.flatMap(interface =>
				List(
					new OutputDirectory(Paths.get(interface.name)),
					new OutputLink(Paths.get(interface.name, moduleFile), input),
					generateLib(interface),
					generate(interface))
			)
		}
	}

	override def generateMakefileRules() = {
		List.empty[MakefileRule]
	}

	override def generateRunCommands() = {
		(if (parent) {
			List(idl.main)
		} else {
			idl.interfaces
		}).map(interface =>
			ExecDescription(
				Array("/usr/bin/python", options.root.relativize(
					options.outputDirectory.resolve(
						Paths.get(interface.name, s"${interface.name}_entry.py")
				)).toString)
			)
		)
	}


	def structFormat(formatType: Type): String = {
		formatType match {
			case primitiveType: PrimitiveType => primitiveType match {
				case PrimitiveType("int") => "'i'"
				case PrimitiveType("long") => "'q'"
				case PrimitiveType("char") => "'c'"
				case PrimitiveType("float") => "'f'"
				case PrimitiveType("double") => "'d'"
				case PrimitiveType("bool") => "'?'"
			}
			case arrayType: ArrayType => {
				"'%d" + structFormat(arrayType.primitive).charAt(1) + "' % " +
						arrayLength(arrayType)
			}
		}
	}

	private def arrayLength(arrayType: ArrayType) = {
			arrayType.lengths.map(_.value).mkString(" * ")
	}

	private def fieldLength(fieldType: Type): String = {
		fieldType match {
			case primitiveType: PrimitiveType =>
				primitiveType match {
					case PrimitiveType("int") => "4"
					case PrimitiveType("long") => "8"
					case PrimitiveType("char") => "1"
					case PrimitiveType("float") => "4"
					case PrimitiveType("double") => "8"
					case PrimitiveType("bool") => "1"
				}
			case arrayType: ArrayType =>
				fieldLength(arrayType.primitive) + " * " + arrayLength(arrayType)
		}
	}

	private def declareFunction(function: Function) = {
		s"def ${function.name}(" + function.params.map(_.name).mkString(", ") + ")"
	}

	private def generateMainEntry() = {
		val builder = new StringBuilder
		builder ++= s"""#!/usr/bin/python
# $message

import sys
import runpy

sys.path[0] = "${options.root.relativize(
	options.outputDirectory.resolve(Paths.get(idl.main.name))).toString}"

runpy.run_module("${idl.main.name}", run_name="__main__")
"""
		OutputFile(
			Paths.get(idl.main.name, s"${idl.main.name}_entry.py"),
			builder.mkString)
	}

	private def generateMain() = {
		val builder = new StringBuilder
		builder ++= s"""#!/usr/bin/python
# $message

import array
import struct
import sys
import time

${generateMessageLoop(
	idl.interfaces.map{
		interface => (interface, idl.main, pipeName(interface))
	},
	pipeName(idl.main)
)}

"""
		idl.interfaces.foreach(interface => {
			interface.functions.foreach(
				builder ++= generateShim(_, interface, idl.main,
					pipeName(interface), pipeName(idl.main), true)
			)
		})
		builder ++= "\n"
		idl.interfaces.foreach(interface => {
			if (options.verbose) {
				builder ++= "print>>sys.stderr," +
						s""" "\\t[${idl.main.name}] opening `${pipeFilename(interface)}'"\n"""
			}
			builder ++=
					s"""${pipeName(interface)} = open("${pipeFilename(interface)}", 'wb')\n"""
		})
		if (options.verbose) {
			builder ++= "print>>sys.stderr," +
					s""" "\\t[${idl.main.name}] opening `${pipeFilename(idl.main)}'"\n"""
		}
		builder ++=
				s"""${pipeName(idl.main)} = open("${pipeFilename(idl.main)}", 'rb')\n"""
		builder ++= s"__elapsed_time = 0\n"
		builder ++= s"import ${idl.main.name}\n"

		OutputFile(
			Paths.get(idl.main.name, s"${options.moduleName}.py"),
			builder.mkString)
	}

	private def generate(interface: Interface) = {
		val builder = new StringBuilder
		builder ++= s"""#!/usr/bin/python
# $message

import ${idl.main.name}
"""
		OutputFile(Paths.get(interface.name, s"${interface.name}_entry.py"), builder.mkString)
	}

	private def generateLib(interface: Interface) = {
		val builder = new StringBuilder
		builder ++= s"""#!/usr/bin/python
# $message

import array
import struct
import sys

${generateMessageLoop(List((idl.main, interface, "__fout")), "__fin")}

${idl.main.functions.map(
	generateShim(_, idl.main, interface, "__fout", "__fin", false).toString
).mkString("\n")}

${if (options.verbose) {
	"print>>sys.stderr, \"\\t[" + interface.name + "] opening `" + pipeFilename(interface) + "'\""
} else ""}
with open("${pipeFilename(interface)}", 'rb') as __fin:
${if (options.verbose) {
	"\tprint>>sys.stderr, \"\\t[" + interface.name + "] opening `" + pipeFilename(idl.main) + "'\""
} else ""}
	with open("${pipeFilename(idl.main)}", 'wb') as __fout:
		import ${options.moduleName}
		__message_loop(-1)
"""
		OutputFile(
			Paths.get(interface.name, s"${idl.main.name}.py"),
			builder.mkString)
	}

	private def generateMessageLoop(interfaces: List[(Interface, Interface, String)], infd: String) = {
		val builder = new StringBuilder
		builder ++= s"""def __message_loop(__current_function):
	global $infd, ${interfaces.map(_._3).mkString(", ")}
	while True:
		__buf = $infd.read(4)
		if len(__buf) == 0:
			break
		elif len(__buf) != 4:
			print>>sys.stderr, "Incomplete message"
			sys.exit(1)
		__msgid = struct.unpack('I', __buf)[0]
		if __msgid == __current_function:
			return\n"""
		for ((caller, callee, outfd) <- interfaces) {
			for (function <- callee.functions) {
				builder ++= f"\t\telif __msgid == 0x${functionIds((caller.name, callee.name, function.name))}%x:\n"
				builder ++= s"\t\t\t# ${caller.name} -> ${callee.name}.${function.name}\n"
				if (options.verbose) {
					builder ++=
						s"""\t\t\tprint>>sys.stderr, "\\t[${callee.name}] calling ${function.name} begin"\n"""
				}
				for (param <- function.params) {
					builder ++= (param.paramType match {
						case array: ArrayType => {
							s"\t\t\t${param.name} = array.array(${structFormat(array.primitive)})\n" +
							s"\t\t\t${param.name}.fromstring($infd.read(${fieldLength(array)}))\n"
						}
						case primitive: PrimitiveType => {
							s"\t\t\t${param.name} = struct.unpack(${structFormat(primitive)}, " +
									s"$infd.read(${fieldLength(primitive)}))[0]\n"
						}
					})
				}
				builder ++= s"\t\t\t__cookie = struct.unpack('I', $infd.read(4))[0]\n"
				builder ++= (if (function.returnType == PrimitiveType("void")) {
					"\t\t\t"
				} else {
					s"\t\t\t__result = "
				})
				builder ++=
					s"""${callee.name}.${function.name}(${function.params.map(_.name).mkString(", ")})\n"""
				builder ++= s"\t\t\t$outfd.write(struct.pack('I', __msgid))\n"
				if (function.returnType != PrimitiveType("void")) {
					builder ++= s"\t\t\t$outfd.write(struct.pack(" +
							s"${structFormat(function.returnType)}, __result))\n"
				}
				builder ++= s"\t\t\t$outfd.write(struct.pack('I', __cookie))\n"
				builder ++= s"\t\t\t$outfd.flush()\n"
				if (options.verbose) {
					builder ++=
						s"""\t\t\tprint>>sys.stderr, "\\t[${callee.name}] calling ${function.name} end"\n"""
				}
			}
		}
		builder ++= """		else:
			print>>sys.stderr, "Unknown message id 0x%x" % __msgid
			sys.exit(1)
	if __current_function != -1:
		print>>sys.stderr, "Confused about exiting"
		sys.exit(1)
"""
		builder
	}

	private def generateShim(function: Function, callee: Interface, caller: Interface,
			outfd: String, infd: String, generateTiming: Boolean) = {
		val builder = new StringBuilder
		builder ++= declareFunction(function)
		builder ++= ":\n"
		if (options.verbose) {
			builder ++=
				s"""\tprint>>sys.stderr, "\\t[${caller.name}] invoking ${function.name} begin\"\n"""
		}
		builder ++= f"\t__msgid = 0x${functionIds((caller.name, callee.name, function.name))}%x\n"
		builder ++= f"\t__cookie = 0x${rand.nextInt}%x\n"
		builder ++= s"\t$outfd.write(struct.pack('I', __msgid))\n"
		function.params.foreach(param => {
			builder ++= (param.paramType match {
				case primitive: PrimitiveType =>
					s"\t$outfd.write(struct.pack(${structFormat(param.paramType)}, " +
						s"${param.name}))\n"
				case array: ArrayType =>
					s"\t$outfd.write(struct.pack(${structFormat(param.paramType)}, " +
						s"*${param.name}))\n"
			})
		})
		if (generateTiming) {
			builder ++=
				"\t__t0 = time.time()\n"
		}
		builder ++= s"\t$outfd.write(struct.pack('I', __cookie))\n"
		builder ++= s"\t$outfd.flush()\n"
		builder ++= "\t__message_loop(__msgid)\n"
		if (function.returnType != PrimitiveType("void")) {
			builder ++= s"\t__ans = struct.unpack(${structFormat(function.returnType)}, " +
					s"$infd.read(${fieldLength(function.returnType)}))[0]\n"
		}
		builder ++= s"\t__cookie_result = struct.unpack('I', $infd.read(4))[0]\n"
		if (generateTiming) {
			builder ++= "\t__t1 = time.time()\n"
			builder ++= "\tglobal __elapsed_time\n"
			builder ++= "\t__elapsed_time += int((__t1 - __t0) * 1e9)\n"
		}

		builder ++= "\tif __cookie != __cookie_result:\n"
		builder ++= "\t\tprint>>sys.stderr, \"invalid cookie\"\n"
		builder ++= "\t\tsys.exit(1)\n"

		if (options.verbose) {
			builder ++=
				s"""\tprint>>sys.stderr, "\\t[${caller.name}] invoking ${function.name} end"\n"""
		}

		if (function.returnType != PrimitiveType("void")) {
			builder ++= "\treturn __ans\n"
		}

		builder
	}
}

/* vim: set noexpandtab: */
