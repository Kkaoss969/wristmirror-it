#!/bin/sh

#
# Copyright © 2015 the original authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# SPDX-License-Identifier: Apache-2.0
#

##############################################################################
#
#   Gradle 生成的标准 POSIX 启动脚本。
#
#   运行注意事项：
#
#   (1) 运行此脚本需要符合 POSIX 标准的 shell。如果 /bin/sh 不符合标准，
#       但系统中有 ksh、bash 等符合标准的 shell，
#       请在完整命令前指定该 shell 名称，
#       例如：
#
#           ksh Gradle
#
#       Busybox 及类似精简 shell 不适用，因为此脚本
#       需要以下全部 POSIX shell 特性：
#         * 函数；
#         * «$var»、«${var}»、«${var:-default}»、«${var+SET}»、
#           «${var#prefix}»、«${var%suffix}» 及 «$( cmd )» 等展开语法；
#         * 可检查退出状态的复合命令，尤其是 «case»；
#         * «command»、«set»、«ulimit» 等内置命令。
#
#   修改注意事项：
#
#   (2) 本脚本面向通用 POSIX shell，因此避免 Bash、Ksh 等特有扩展，
#       尤其不使用数组。
#
#       将多个参数拼成以空格分隔字符串的传统做法，
#       容易引入已知缺陷与安全问题，
#       因此本脚本主要通过逐步累积选项到 "$@"，
#       再将其传递给 Java，来避免这种做法。
#
#       继承的环境变量 DEFAULT_JVM_OPTS、JAVA_OPTS
#       与 GRADLE_OPTS 如需按词拆分，将显式执行；
#       详见对应行内注释。
#
#       脚本包含针对 AIX、CygWin、Darwin、MinGW
#       与 NonStop 等特定操作系统的适配。
#
#   (3) 本脚本由以下 Groovy 模板生成：
#       https://github.com/gradle/gradle/blob/2d6327017519d23b96af35865dc997fcb544fb40/platforms/jvm/plugins-application/src/main/resources/org/gradle/api/internal/plugins/unixStartScript.txt
#       该模板属于 Gradle 工程。
#
#       Gradle 工程地址：https://github.com/gradle/gradle/。
#
##############################################################################

# 尝试确定 APP_HOME

# 解析符号链接：$0 可能指向链接
app_path=$0

# 逐级处理串联的符号链接。
while
    APP_HOME=${app_path%"${app_path##*/}"}  # 保留末尾 /；没有前置路径时为空
    [ -h "$app_path" ]
do
    ls=$( ls -ld "$app_path" )
    link=${ls#*' -> '}
    case $link in             #(
      /*)   app_path=$link ;; #(
      *)    app_path=$APP_HOME$link ;;
    esac
done

# 此变量通常不使用
# shellcheck disable=SC2034
APP_BASE_NAME=${0##*/}
# 丢弃 cd 的标准输出，避免已设置的 $CDPATH 影响结果（https://github.com/gradle/gradle/issues/25036）
APP_HOME=$( cd -P "${APP_HOME:-./}" > /dev/null && printf '%s\n' "$PWD" ) || exit

# 使用可用的最大值；也可将 MAX_FD 设为不等于 -1 的指定值。
MAX_FD=maximum

warn () {
    echo "$*"
} >&2

die () {
    echo
    echo "$*"
    echo
    exit 1
} >&2

# 操作系统适配标志必须为 true 或 false。
cygwin=false
msys=false
darwin=false
nonstop=false
case "$( uname )" in                #(
  CYGWIN* )         cygwin=true  ;; #(
  Darwin* )         darwin=true  ;; #(
  MSYS* | MINGW* )  msys=true    ;; #(
  NONSTOP* )        nonstop=true ;;
esac



# 确定启动 JVM 所使用的 Java 命令。
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        # AIX 上的 IBM JDK 使用特殊的可执行文件位置
        JAVACMD=$JAVA_HOME/jre/sh/java
    else
        JAVACMD=$JAVA_HOME/bin/java
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
else
    JAVACMD=java
    if ! command -v java >/dev/null 2>&1
    then
        die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
fi

# 如条件允许，提高最大文件描述符数量。
if ! "$cygwin" && ! "$darwin" && ! "$nonstop" ; then
    case $MAX_FD in #(
      max*)
        # POSIX sh 未规定 ulimit -H，因此检查返回结果以确认操作是否成功。
        # shellcheck disable=SC2039,SC3045
        MAX_FD=$( ulimit -H -n ) ||
            warn "Could not query maximum file descriptor limit"
    esac
    case $MAX_FD in  #(
      '' | soft) :;; #(
      *)
        # POSIX sh 未规定 ulimit -n，因此检查返回结果以确认操作是否成功。
        # shellcheck disable=SC2039,SC3045
        ulimit -n "$MAX_FD" ||
            warn "Could not set maximum file descriptor limit to $MAX_FD"
    esac
fi

# 收集 Java 命令的全部参数，按倒序累积：
#   * 来自命令行的参数
#   * 主类名称
#   * -classpath
#   * -D...appname 设置
#   * --module-path（仅在需要时）
#   * DEFAULT_JVM_OPTS、JAVA_OPTS 与 GRADLE_OPTS 环境变量。

# 在 Cygwin 或 MSYS 中，运行 Java 前将路径转换为 Windows 格式
if "$cygwin" || "$msys" ; then
    APP_HOME=$( cygpath --path --mixed "$APP_HOME" )

    JAVACMD=$( cygpath --unix "$JAVACMD" )

    # 转换参数，使用适配写法以维持 /bin/sh 兼容
    for arg do
        if
            case $arg in                                #(
              -*)   false ;;                            # 不改动选项 #(
              /?*)  t=${arg#/} t=/${t%%/*}              # 看起来是 POSIX 文件路径
                    [ -e "$t" ] ;;                      #(
              *)    false ;;
            esac
        then
            arg=$( cygpath --path --ignore --mixed "$arg" )
        fi
        # 循环参数列表，次数恰好等于参数数量，
        # 使每个参数最终回到原来的位置，
        # 但其内容可能已完成路径转换。
        #
        # 注意：for 循环开始前已固定迭代列表，
        # 因此在此更改位置参数，不会影响迭代次数，
        # 也不会影响 arg 每次取得的值。
        shift                   # 移除原参数
        set -- "$@" "$arg"      # 追加替换后的参数
    done
fi


# 在此设置默认 JVM 选项；也可通过 JAVA_OPTS 与 GRADLE_OPTS 向脚本传入 JVM 选项。
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# 收集 Java 命令的全部参数：
#   * DEFAULT_JVM_OPTS、JAVA_OPTS 与 optsEnvironmentVar 不允许包含 shell 代码片段，
#     其中可能被 shell 解释的内容将进行转义。
#   * 例如：不能期望环境变量 ${Hostname} 在此展开，
#     它将在命令行中保留为字面量 ${Hostname}。

set -- \
        "-Dorg.gradle.appname=$APP_BASE_NAME" \
        -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
        "$@"

# 找不到 xargs 时停止运行。
if ! command -v xargs >/dev/null 2>&1
then
    die "xargs is not available"
fi

# 使用 xargs 解析带引号的参数。
#
# 使用 -n1 时每行输出一个参数，并移除引号与反斜线。
#
# 在 Bash 中可以直接使用：
#
#   readarray ARGS < <( xargs -n1 <<<"$var" ) &&
#   set -- "${ARGS[@]}" "$@"
#
# 但 POSIX shell 不支持此处所用的数组与 Bash 替换语法，因此改为
# 对每个参数分别后处理，将其按行交给 sed，
# 对可能成为 shell 元字符的字符添加反斜线转义，
# 再用 eval 还原转义，同时保留参数之间的分隔，
# 最终将整组参数包装为一条 set 语句。
#
# 如果这些变量中包含换行符或
# 未配对的引号，此处理会失败。
#

eval "set -- $(
        printf '%s\n' "$DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS" |
        xargs -n1 |
        sed ' s~[^-[:alnum:]+,./:=@_]~\\&~g; ' |
        tr '\n' ' '
    )" '"$@"'

exec "$JAVACMD" "$@"
