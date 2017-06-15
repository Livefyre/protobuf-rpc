import socket
import os
import psutil
__author__ = 'tim'
__hostname__ = socket.gethostname()
__pid__ = os.getpid()
__procname__ = psutil.Process(__pid__).name()