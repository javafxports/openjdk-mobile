/*
 * Copyright (c) 1997, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
#include <malloc.h>

#include "net_util.h"

#include "java_net_TwoStacksPlainSocketImpl.h"
#include "java_net_SocketOptions.h"
#include "java_net_InetAddress.h"

/************************************************************************
 * TwoStacksPlainSocketImpl
 */

static jfieldID IO_fd_fdID;

jfieldID psi_fdID;
jfieldID psi_addressID;
jfieldID psi_portID;
jfieldID psi_localportID;
jfieldID psi_timeoutID;
jfieldID psi_trafficClassID;
jfieldID psi_serverSocketID;

/*
 * the level of the TCP protocol for setsockopt and getsockopt
 * we only want to look this up once, from the static initializer
 * of TwoStacksPlainSocketImpl
 */
static int tcp_level = -1;

static int getFD(JNIEnv *env, jobject this) {
    jobject fdObj = (*env)->GetObjectField(env, this, psi_fdID);

    if (fdObj == NULL) {
        return -1;
    }
    return (*env)->GetIntField(env, fdObj, IO_fd_fdID);
}


/*
 * The initProto function is called whenever TwoStacksPlainSocketImpl is
 * loaded, to cache fieldIds for efficiency. This is called everytime
 * the Java class is loaded.
 *
 * Class:     java_net_TwoStacksPlainSocketImpl
 * Method:    initProto

 * Signature: ()V
 */
JNIEXPORT void JNICALL
Java_java_net_TwoStacksPlainSocketImpl_initProto(JNIEnv *env, jclass cls) {

    struct protoent *proto = getprotobyname("TCP");
    tcp_level = (proto == 0 ? IPPROTO_TCP: proto->p_proto);

    psi_fdID = (*env)->GetFieldID(env, cls , "fd", "Ljava/io/FileDescriptor;");
    CHECK_NULL(psi_fdID);
    psi_addressID = (*env)->GetFieldID(env, cls, "address",
                                          "Ljava/net/InetAddress;");
    CHECK_NULL(psi_addressID);
    psi_portID = (*env)->GetFieldID(env, cls, "port", "I");
    CHECK_NULL(psi_portID);
    psi_localportID = (*env)->GetFieldID(env, cls, "localport", "I");
    CHECK_NULL(psi_localportID);
    psi_timeoutID = (*env)->GetFieldID(env, cls, "timeout", "I");
    CHECK_NULL(psi_timeoutID);
    psi_trafficClassID = (*env)->GetFieldID(env, cls, "trafficClass", "I");
    CHECK_NULL(psi_trafficClassID);
    psi_serverSocketID = (*env)->GetFieldID(env, cls, "serverSocket",
                                            "Ljava/net/ServerSocket;");
    CHECK_NULL(psi_serverSocketID);
    IO_fd_fdID = NET_GetFileDescriptorID(env);
    CHECK_NULL(IO_fd_fdID);
}

/*
 * Class:     java_net_TwoStacksPlainSocketImpl
 * Method:    socketCreate
 * Signature: (Z)V
 */
JNIEXPORT void JNICALL
Java_java_net_TwoStacksPlainSocketImpl_socketCreate(JNIEnv *env, jobject this,
                                           jboolean stream) {
    jobject fdObj;
    int fd;

    fdObj = (*env)->GetObjectField(env, this, psi_fdID);

    if (IS_NULL(fdObj)) {
        JNU_ThrowByName(env, JNU_JAVANETPKG "SocketException",
                        "null fd object");
        return;
    }
    fd = socket(AF_INET, (stream ? SOCK_STREAM: SOCK_DGRAM), 0);
    if (fd == -1) {
        NET_ThrowCurrent(env, "create");
        return;
    } else {
        /* Set socket attribute so it is not passed to any child process */
        SetHandleInformation((HANDLE)(UINT_PTR)fd, HANDLE_FLAG_INHERIT, FALSE);
        (*env)->SetIntField(env, fdObj, IO_fd_fdID, (int)fd);
    }
}

/*
 * inetAddress is the address object passed to the socket connect
 * call.
 *
 * Class:     java_net_TwoStacksPlainSocketImpl
 * Method:    socketConnect
 * Signature: (Ljava/net/InetAddress;I)V
 */
JNIEXPORT void JNICALL
Java_java_net_TwoStacksPlainSocketImpl_socketConnect(JNIEnv *env, jobject this,
                                                     jobject iaObj, jint port,
                                                     jint timeout)
{
    jint localport = (*env)->GetIntField(env, this, psi_localportID);

    /* family and localport are int fields of iaObj */
    int family;
    jint fd = -1;
    jint len;

    jobject fdObj = (*env)->GetObjectField(env, this, psi_fdID);

    SOCKETADDRESS sa;

    /* The result of the connection */
    int connect_res;
    memset((char *)&sa, 0, sizeof(sa));

    if (!IS_NULL(fdObj)) {
        fd = (*env)->GetIntField(env, fdObj, IO_fd_fdID);
    }

    if (IS_NULL(iaObj)) {
        JNU_ThrowNullPointerException(env, "inet address argument is null.");
        return;
    }

    if (NET_InetAddressToSockaddr(env, iaObj, port, &sa, &len,
                                  JNI_FALSE) != 0) {
        return;
    }

    family = sa.sa.sa_family;
    if (family != AF_INET) {
        JNU_ThrowByName(env, JNU_JAVANETPKG "SocketException",
                        "Protocol family not supported");
    }
    if (fd == -1) {
        JNU_ThrowByName(env, JNU_JAVANETPKG "SocketException",
                        "Destination unreachable");
        return;
    }

    if (timeout <= 0) {
        connect_res = connect(fd, &sa.sa, sizeof(SOCKETADDRESS));
        if (connect_res == SOCKET_ERROR) {
            connect_res = WSAGetLastError();
        }
    } else {
        int optval;
        int optlen = sizeof(optval);

        /* make socket non-blocking */
        optval = 1;
        ioctlsocket(fd, FIONBIO, &optval);

        /* initiate the connect */
        connect_res = connect(fd, &sa.sa, sizeof(SOCKETADDRESS));
        if (connect_res == SOCKET_ERROR) {
            if (WSAGetLastError() != WSAEWOULDBLOCK) {
                connect_res = WSAGetLastError();
            } else {
                fd_set wr, ex;
                struct timeval t;

                FD_ZERO(&wr);
                FD_ZERO(&ex);
                FD_SET(fd, &wr);
                FD_SET(fd, &ex);
                t.tv_sec = timeout / 1000;
                t.tv_usec = (timeout % 1000) * 1000;

                /*
                 * Wait for timout, connection established or
                 * connection failed.
                 */
                connect_res = select(fd+1, 0, &wr, &ex, &t);

                /*
                 * Timeout before connection is established/failed so
                 * we throw exception and shutdown input/output to prevent
                 * socket from being used.
                 * The socket should be closed immediately by the caller.
                 */
                if (connect_res == 0) {
                    JNU_ThrowByName(env, JNU_JAVANETPKG "SocketTimeoutException",
                                    "connect timed out");
                    shutdown( fd, SD_BOTH );

                     /* make socket blocking again - just in case */
                    optval = 0;
                    ioctlsocket( fd, FIONBIO, &optval );
                    return;
                }

                /*
                 * We must now determine if the connection has been established
                 * or if it has failed. The logic here is designed to work around
                 * bug on Windows NT whereby using getsockopt to obtain the
                 * last error (SO_ERROR) indicates there is no error. The workaround
                 * on NT is to allow winsock to be scheduled and this is done by
                 * yielding and retrying. As yielding is problematic in heavy
                 * load conditions we attempt up to 3 times to get the error reason.
                 */
                if (!FD_ISSET(fd, &ex)) {
                    connect_res = 0;
                } else {
                    int retry;
                    for (retry=0; retry<3; retry++) {
                        NET_GetSockOpt(fd, SOL_SOCKET, SO_ERROR,
                                       (char*)&connect_res, &optlen);
                        if (connect_res) {
                            break;
                        }
                        Sleep(0);
                    }

                    if (connect_res == 0) {
                        JNU_ThrowByName(env, JNU_JAVANETPKG "SocketException",
                                        "Unable to establish connection");
                        return;
                    }
                }
            }
        }

        /* make socket blocking again */
        optval = 0;
        ioctlsocket(fd, FIONBIO, &optval);
    }

    if (connect_res) {
        if (connect_res == WSAEADDRNOTAVAIL) {
            JNU_ThrowByName(env, JNU_JAVANETPKG "ConnectException",
                "connect: Address is invalid on local machine, or port is not valid on remote machine");
        } else {
            NET_ThrowNew(env, connect_res, "connect");
        }
        return;
    }

    (*env)->SetIntField(env, fdObj, IO_fd_fdID, (int)fd);

    /* set the remote peer address and port */
    (*env)->SetObjectField(env, this, psi_addressID, iaObj);
    (*env)->SetIntField(env, this, psi_portID, port);

    /*
     * we need to initialize the local port field if bind was called
     * previously to the connect (by the client) then localport field
     * will already be initialized
     */
    if (localport == 0) {
        /* Now that we're a connected socket, let's extract the port number
         * that the system chose for us and store it in the Socket object.
         */
        u_short port;
        int len = sizeof(SOCKETADDRESS);
        if (getsockname(fd, &sa.sa, &len) == -1) {
            if (WSAGetLastError() == WSAENOTSOCK) {
                JNU_ThrowByName(env, JNU_JAVANETPKG "SocketException",
                                "Socket closed");
            } else {
                NET_ThrowCurrent(env, "getsockname failed");
            }
            return;
        }
        port = ntohs((u_short)GET_PORT(&sa));
        (*env)->SetIntField(env, this, psi_localportID, (int) port);
    }
}

/*
 * Class:     java_net_TwoStacksPlainSocketImpl
 * Method:    socketBind
 * Signature: (Ljava/net/InetAddress;I)V
 */
JNIEXPORT void JNICALL
Java_java_net_TwoStacksPlainSocketImpl_socketBind(JNIEnv *env, jobject this,
                                         jobject iaObj, jint localport,
                                         jboolean exclBind) {

    /* fdObj is the FileDescriptor field on this */
    jobject fdObj;
    /* fd is an int field on fdObj */
    int fd, len = 0;

    /* family is an int field of iaObj */
    int family;
    int rv;

    SOCKETADDRESS sa;

    fdObj = (*env)->GetObjectField(env, this, psi_fdID);

    family = getInetAddress_family(env, iaObj);

    if (family != java_net_InetAddress_IPv4) {
        JNU_ThrowByName(env, JNU_JAVANETPKG "SocketException",
                        "Protocol family not supported");
        return;
    }

    if (IS_NULL(fdObj)) {
        JNU_ThrowByName(env, JNU_JAVANETPKG "SocketException",
                        "Socket closed");
        return;
    } else {
        fd = (*env)->GetIntField(env, fdObj, IO_fd_fdID);
    }
    if (IS_NULL(iaObj)) {
        JNU_ThrowNullPointerException(env, "inet address argument");
        return;
    }

    if (NET_InetAddressToSockaddr(env, iaObj, localport, &sa, &len,
                                  JNI_FALSE) != 0) {
        return;
    }
    rv = NET_WinBind(fd, &sa, len, exclBind);

    if (rv == -1) {
        NET_ThrowCurrent(env, "NET_Bind");
        return;
    }

    /* set the address */
    (*env)->SetObjectField(env, this, psi_addressID, iaObj);

    /* intialize the local port */
    if (localport == 0) {
        /* Now that we're a bound socket, let's extract the port number
         * that the system chose for us and store it in the Socket object.
         */
        int len = sizeof(SOCKETADDRESS);
        u_short port;

        if (getsockname(fd, &sa.sa, &len) == -1) {
            NET_ThrowCurrent(env, "getsockname in plain socketBind");
            return;
        }
        port = ntohs((u_short) GET_PORT (&sa));

        (*env)->SetIntField(env, this, psi_localportID, (int)port);
    } else {
        (*env)->SetIntField(env, this, psi_localportID, localport);
    }
}

/*
 * Class:     java_net_TwoStacksPlainSocketImpl
 * Method:    socketListen
 * Signature: (I)V
 */
JNIEXPORT void JNICALL
Java_java_net_TwoStacksPlainSocketImpl_socketListen
  (JNIEnv *env, jobject this, jint count)
{
    /* this FileDescriptor fd field */
    jobject fdObj = (*env)->GetObjectField(env, this, psi_fdID);
    /* fdObj's int fd field */
    int fd;

    if (IS_NULL(fdObj)) {
        JNU_ThrowByName(env, JNU_JAVANETPKG "SocketException",
                        "socket closed");
        return;
    } else {
        fd = (*env)->GetIntField(env, fdObj, IO_fd_fdID);
    }

    if (listen(fd, count) == -1) {
        NET_ThrowCurrent(env, "listen failed");
    }

}

/*
 * Class:     java_net_TwoStacksPlainSocketImpl
 * Method:    socketAccept
 * Signature: (Ljava/net/SocketImpl;)V
 */
JNIEXPORT void JNICALL
Java_java_net_TwoStacksPlainSocketImpl_socketAccept(JNIEnv *env, jobject this,
                                           jobject socket)
{
    /* fields on this */
    jint port;
    jint timeout = (*env)->GetIntField(env, this, psi_timeoutID);
    jobject fdObj = (*env)->GetObjectField(env, this, psi_fdID);

    /* the FileDescriptor field on socket */
    jobject socketFdObj;

    /* cache the Inet4 class */
    static jclass inet4Cls;

    /* the InetAddress field on socket */
    jobject socketAddressObj;

    /* the fd int field on fdObj */
    jint fd=-1;

    SOCKETADDRESS sa;
    jint len;
    int ret;

    if (IS_NULL(fdObj)) {
        JNU_ThrowByName(env, JNU_JAVANETPKG "SocketException",
                        "Socket closed");
        return;
    }

    fd = (*env)->GetIntField(env, fdObj, IO_fd_fdID);

    if (IS_NULL(socket)) {
        JNU_ThrowNullPointerException(env, "socket is null");
        return;
    } else {
        socketFdObj = (*env)->GetObjectField(env, socket, psi_fdID);
        socketAddressObj = (*env)->GetObjectField(env, socket, psi_addressID);
    }
    if ((IS_NULL(socketAddressObj)) || (IS_NULL(socketFdObj))) {
        JNU_ThrowNullPointerException(env, "socket address or fd obj");
        return;
    }

    len = sizeof(struct sockaddr_in);
    if (timeout) {
        ret = NET_Timeout(fd, timeout);
        if (ret == 0) {
            JNU_ThrowByName(env, JNU_JAVANETPKG "SocketTimeoutException",
                            "Accept timed out");
            return;
        } else if (ret == -1) {
            JNU_ThrowByName(env, JNU_JAVANETPKG "SocketException", "socket closed");
        /* REMIND: SOCKET CLOSED PROBLEM */
/*        NET_ThrowCurrent(env, "Accept failed"); */
            return;
        } else if (ret == -2) {
            JNU_ThrowByName(env, JNU_JAVAIOPKG "InterruptedIOException",
                            "operation interrupted");
            return;
        }
    }

    fd = accept(fd, &sa.sa, &len);
    if (fd < 0) {
        /* REMIND: SOCKET CLOSED PROBLEM */
        if (fd == -2) {
            JNU_ThrowByName(env, JNU_JAVAIOPKG "InterruptedIOException",
                            "operation interrupted");
        } else {
            JNU_ThrowByName(env, JNU_JAVANETPKG "SocketException",
                            "socket closed");
        }
        return;
    }
    SetHandleInformation((HANDLE)(UINT_PTR)fd, HANDLE_FLAG_INHERIT, 0);
    (*env)->SetIntField(env, socketFdObj, IO_fd_fdID, fd);

    if (sa.sa.sa_family != AF_INET) {
        JNU_ThrowByName(env, JNU_JAVANETPKG "SocketException",
                        "Protocol family not supported");
        return;
    }

    if (inet4Cls == NULL) {
        jclass c = (*env)->FindClass(env, "java/net/Inet4Address");
        if (c != NULL) {
            inet4Cls = (*env)->NewGlobalRef(env, c);
            (*env)->DeleteLocalRef(env, c);
        }
    }

    /*
     * fill up the remote peer port and address in the new socket structure
     */
    if (inet4Cls != NULL) {
        socketAddressObj = (*env)->NewObject(env, inet4Cls, ia4_ctrID);
    } else {
        socketAddressObj = NULL;
    }
    if (socketAddressObj == NULL) {
        /*
         * FindClass or NewObject failed so close connection and
         * exit (there will be a pending exception).
         */
        NET_SocketClose(fd);
        return;
    }

    setInetAddress_addr(env, socketAddressObj, ntohl(sa.sa4.sin_addr.s_addr));
    setInetAddress_family(env, socketAddressObj, java_net_InetAddress_IPv4);
    (*env)->SetObjectField(env, socket, psi_addressID, socketAddressObj);
    port = ntohs ((u_short)GET_PORT(&sa));
    (*env)->SetIntField(env, socket, psi_portID, (int)port);
    port = (*env)->GetIntField(env, this, psi_localportID);
    (*env)->SetIntField(env, socket, psi_localportID, port);
    (*env)->SetObjectField(env, socket, psi_addressID, socketAddressObj);
}

/*
 * Class:     java_net_TwoStacksPlainSocketImpl
 * Method:    socketAvailable
 * Signature: ()I
 */
JNIEXPORT jint JNICALL
Java_java_net_TwoStacksPlainSocketImpl_socketAvailable(JNIEnv *env, jobject this) {

    jint available = -1;
    jint res;
    jobject fdObj = (*env)->GetObjectField(env, this, psi_fdID);
    jint fd;

    if (IS_NULL(fdObj)) {
        JNU_ThrowByName(env, JNU_JAVANETPKG "SocketException", "Socket closed");
        return -1;
    } else {
        fd = (*env)->GetIntField(env, fdObj, IO_fd_fdID);
    }
    res = ioctlsocket(fd, FIONREAD, &available);
    /* if result isn't 0, it means an error */
    if (res != 0) {
        NET_ThrowNew(env, res, "socket available");
    }
    return available;
}

/*
 * Class:     java_net_TwoStacksPlainSocketImpl
 * Method:    socketClose
 * Signature: ()V
 */
JNIEXPORT void JNICALL
Java_java_net_TwoStacksPlainSocketImpl_socketClose0(JNIEnv *env, jobject this,
                                           jboolean useDeferredClose) {

    jobject fdObj = (*env)->GetObjectField(env, this, psi_fdID);
    jint fd=-1;

    if (IS_NULL(fdObj)) {
        JNU_ThrowByName(env, JNU_JAVANETPKG "SocketException",
                        "socket already closed");
        return;
    }
    if (!IS_NULL(fdObj)) {
        fd = (*env)->GetIntField(env, fdObj, IO_fd_fdID);
    }
    if (fd != -1) {
        (*env)->SetIntField(env, fdObj, IO_fd_fdID, -1);
        NET_SocketClose(fd);
    }
}

/*
 * Socket options for plainsocketImpl
 *
 *
 * Class:     java_net_TwoStacksPlainSocketImpl
 * Method:    socketNativeSetOption
 * Signature: (IZLjava/lang/Object;)V
 */
JNIEXPORT void JNICALL
Java_java_net_TwoStacksPlainSocketImpl_socketNativeSetOption
  (JNIEnv *env, jobject this, jint cmd, jboolean on, jobject value)
{
    int fd;
    int level = 0, optname = 0, optlen = 0;
    union {
        int i;
        struct linger ling;
    } optval;

    memset((char *)&optval, 0, sizeof(optval));
    /*
     * Get SOCKET and check that it hasn't been closed
     */
    fd = getFD(env, this);
    if (fd < 0) {
        JNU_ThrowByName(env, JNU_JAVANETPKG "SocketException", "Socket closed");
        return;
    }

    /*
     * SO_TIMEOUT is the socket option used to specify the timeout
     * for ServerSocket.accept and Socket.getInputStream().read.
     * It does not typically map to a native level socket option.
     * For Windows we special-case this and use the SOL_SOCKET/SO_RCVTIMEO
     * socket option to specify a receive timeout on the socket. This
     * receive timeout is applicable to Socket only and the socket
     * option should not be set on ServerSocket.
     */
    if (cmd == java_net_SocketOptions_SO_TIMEOUT) {

        /*
         * Don't enable the socket option on ServerSocket as it's
         * meaningless (we don't receive on a ServerSocket).
         */
        jobject ssObj = (*env)->GetObjectField(env, this, psi_serverSocketID);
        if (ssObj != NULL) {
            return;
        }

        /*
         * SO_RCVTIMEO is only supported on Microsoft's implementation
         * of Windows Sockets so if WSAENOPROTOOPT returned then
         * reset flag and timeout will be implemented using
         * select() -- see SocketInputStream.socketRead.
         */
        if (isRcvTimeoutSupported) {
            jclass iCls = (*env)->FindClass(env, "java/lang/Integer");
            jfieldID i_valueID;
            jint timeout;

            CHECK_NULL(iCls);
            i_valueID = (*env)->GetFieldID(env, iCls, "value", "I");
            CHECK_NULL(i_valueID);
            timeout = (*env)->GetIntField(env, value, i_valueID);

            /*
             * Disable SO_RCVTIMEO if timeout is <= 5 second.
             */
            if (timeout <= 5000) {
                timeout = 0;
            }

            if (setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, (char *)&timeout,
                sizeof(timeout)) < 0) {
                if (WSAGetLastError() == WSAENOPROTOOPT) {
                    isRcvTimeoutSupported = JNI_FALSE;
                } else {
                    NET_ThrowCurrent(env, "setsockopt SO_RCVTIMEO");
                    return;
                }
            }
        }
        return;
    }

    /*
     * Map the Java level socket option to the platform specific
     * level and option name.
     */
    if (NET_MapSocketOption(cmd, &level, &optname)) {
        JNU_ThrowByName(env, "java/net/SocketException", "Invalid option");
        return;
    }

    switch (cmd) {

        case java_net_SocketOptions_TCP_NODELAY :
        case java_net_SocketOptions_SO_OOBINLINE :
        case java_net_SocketOptions_SO_KEEPALIVE :
        case java_net_SocketOptions_SO_REUSEADDR :
            optval.i = (on ? 1 : 0);
            optlen = sizeof(optval.i);
            break;

        case java_net_SocketOptions_SO_SNDBUF :
        case java_net_SocketOptions_SO_RCVBUF :
        case java_net_SocketOptions_IP_TOS :
            {
                jclass cls;
                jfieldID fid;

                cls = (*env)->FindClass(env, "java/lang/Integer");
                CHECK_NULL(cls);
                fid = (*env)->GetFieldID(env, cls, "value", "I");
                CHECK_NULL(fid);

                optval.i = (*env)->GetIntField(env, value, fid);
                optlen = sizeof(optval.i);
            }
            break;

        case java_net_SocketOptions_SO_LINGER :
            {
                jclass cls;
                jfieldID fid;

                cls = (*env)->FindClass(env, "java/lang/Integer");
                CHECK_NULL(cls);
                fid = (*env)->GetFieldID(env, cls, "value", "I");
                CHECK_NULL(fid);

                if (on) {
                    optval.ling.l_onoff = 1;
                    optval.ling.l_linger =
                        (unsigned short)(*env)->GetIntField(env, value, fid);
                } else {
                    optval.ling.l_onoff = 0;
                    optval.ling.l_linger = 0;
                }
                optlen = sizeof(optval.ling);
            }
            break;

        default: /* shouldn't get here */
            JNU_ThrowByName(env, JNU_JAVANETPKG "SocketException",
                "Option not supported by TwoStacksPlainSocketImpl");
            return;
    }

    if (fd != -1) {
        if (NET_SetSockOpt(fd, level, optname, (void *)&optval, optlen) < 0) {
            NET_ThrowCurrent(env, "setsockopt");
        }
    }
}


/*
 * Class:     java_net_TwoStacksPlainSocketImpl
 * Method:    socketGetOption
 * Signature: (I)I
 */
JNIEXPORT jint JNICALL
Java_java_net_TwoStacksPlainSocketImpl_socketGetOption
  (JNIEnv *env, jobject this, jint opt, jobject iaContainerObj)
{
    int fd;
    int level = 0, optname = 0, optlen = 0;
    union {
        int i;
        struct linger ling;
    } optval;

    /*
     * Get SOCKET and check it hasn't been closed
     */
    fd = getFD(env, this);
    memset((char *)&optval, 0, sizeof(optval));

    if (fd < 0) {
        JNU_ThrowByName(env, JNU_JAVANETPKG "SocketException", "Socket closed");
        return -1;
    }

    /*
     * SO_BINDADDR isn't a socket option
     */
    if (opt == java_net_SocketOptions_SO_BINDADDR) {
        SOCKETADDRESS sa;
        int len = sizeof(SOCKETADDRESS);
        int port;
        jobject iaObj;
        jclass iaCntrClass;
        jfieldID iaFieldID;

        memset((char *)&sa, 0, len);

        if (getsockname(fd, &sa.sa, &len) < 0) {
            JNU_ThrowByNameWithMessageAndLastError
                (env, JNU_JAVANETPKG "SocketException", "Error getting socket name");
            return -1;
        }
        iaObj = NET_SockaddrToInetAddress(env, &sa, &port);
        CHECK_NULL_RETURN(iaObj, -1);

        iaCntrClass = (*env)->GetObjectClass(env, iaContainerObj);
        iaFieldID = (*env)->GetFieldID(env, iaCntrClass, "addr", "Ljava/net/InetAddress;");
        CHECK_NULL_RETURN(iaFieldID, -1);
        (*env)->SetObjectField(env, iaContainerObj, iaFieldID, iaObj);
        return 0; /* notice change from before */
    }

    /*
     * Map the Java level socket option to the platform specific
     * level and option name.
     */
    if (NET_MapSocketOption(opt, &level, &optname)) {
        JNU_ThrowByName(env, "java/net/SocketException", "Invalid option");
        return -1;
    }

    /*
     * Args are int except for SO_LINGER
     */
    if (opt == java_net_SocketOptions_SO_LINGER) {
        optlen = sizeof(optval.ling);
    } else {
        optlen = sizeof(optval.i);
        optval.i = 0;
    }

    if (NET_GetSockOpt(fd, level, optname, (void *)&optval, &optlen) < 0) {
        NET_ThrowCurrent(env, "getsockopt");
        return -1;
    }

    switch (opt) {
        case java_net_SocketOptions_SO_LINGER:
            return (optval.ling.l_onoff ? optval.ling.l_linger: -1);

        case java_net_SocketOptions_SO_SNDBUF:
        case java_net_SocketOptions_SO_RCVBUF:
        case java_net_SocketOptions_IP_TOS:
            return optval.i;

        case java_net_SocketOptions_TCP_NODELAY :
        case java_net_SocketOptions_SO_OOBINLINE :
        case java_net_SocketOptions_SO_KEEPALIVE :
        case java_net_SocketOptions_SO_REUSEADDR :
            return (optval.i == 0) ? -1 : 1;

        default: /* shouldn't get here */
            JNU_ThrowByName(env, JNU_JAVANETPKG "SocketException",
                "Option not supported by TwoStacksPlainSocketImpl");
            return -1;
    }
}

/*
 * Class:     java_net_TwoStacksPlainSocketImpl
 * Method:    socketShutdown
 * Signature: (I)V
 */
JNIEXPORT void JNICALL
Java_java_net_TwoStacksPlainSocketImpl_socketShutdown(JNIEnv *env, jobject this,
                                             jint howto)
{

    jobject fdObj = (*env)->GetObjectField(env, this, psi_fdID);
    jint fd;

    /*
     * WARNING: THIS NEEDS LOCKING. ALSO: SHOULD WE CHECK for fd being
     * -1 already?
     */
    if (IS_NULL(fdObj)) {
        JNU_ThrowByName(env, JNU_JAVANETPKG "SocketException",
                        "socket already closed");
        return;
    } else {
        fd = (*env)->GetIntField(env, fdObj, IO_fd_fdID);
    }
    shutdown(fd, howto);
}

/*
 * Class:     java_net_TwoStacksPlainSocketImpl
 * Method:    socketSendUrgentData
 * Signature: (B)V
 */
JNIEXPORT void JNICALL
Java_java_net_TwoStacksPlainSocketImpl_socketSendUrgentData(JNIEnv *env, jobject this,
                                             jint data) {
    /* The fd field */
    jobject fdObj = (*env)->GetObjectField(env, this, psi_fdID);
    int n, fd;
    unsigned char d = data & 0xff;

    if (IS_NULL(fdObj)) {
        JNU_ThrowByName(env, "java/net/SocketException", "Socket closed");
        return;
    } else {
        fd = (*env)->GetIntField(env, fdObj, IO_fd_fdID);
        /* Bug 4086704 - If the Socket associated with this file descriptor
         * was closed (sysCloseFD), the file descriptor is set to -1.
         */
        if (fd == -1) {
            JNU_ThrowByName(env, "java/net/SocketException", "Socket closed");
            return;
        }

    }
    n = send(fd, (char *)&data, 1, MSG_OOB);
    if (n == -1) {
        NET_ThrowCurrent(env, "send");
        return;
    }
}
